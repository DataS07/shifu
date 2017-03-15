/*
 * Copyright [2012-2014] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import ml.shifu.guagua.hadoop.util.HDPUtils;
import ml.shifu.guagua.mapreduce.GuaguaMapReduceConstants;
import ml.shifu.guagua.util.NumberFormatUtils;
import ml.shifu.shifu.actor.AkkaSystemExecutor;
import ml.shifu.shifu.container.obj.ModelNormalizeConf.Correlation;
import ml.shifu.shifu.container.obj.ModelNormalizeConf.NormType;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.core.correlation.CorrelationMapper;
import ml.shifu.shifu.core.correlation.CorrelationReducer;
import ml.shifu.shifu.core.correlation.CorrelationWritable;
import ml.shifu.shifu.core.dtrain.nn.NNConstants;
import ml.shifu.shifu.core.mr.input.CombineInputFormat;
import ml.shifu.shifu.core.shuffle.MapReduceShuffle;
import ml.shifu.shifu.core.validator.ModelInspector.ModelStep;
import ml.shifu.shifu.exception.ShifuErrorCode;
import ml.shifu.shifu.exception.ShifuException;
import ml.shifu.shifu.fs.ShifuFileUtils;
import ml.shifu.shifu.pig.PigExecutor;
import ml.shifu.shifu.util.CommonUtils;
import ml.shifu.shifu.util.Constants;
import ml.shifu.shifu.util.Environment;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.Predicate;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.jexl2.JexlException;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.map.MultithreadedMapper;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.pig.impl.util.JarManager;
import org.apache.pig.tools.pigstats.JobStats;
import org.apache.pig.tools.pigstats.PigStats;
import org.encog.ml.data.MLDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;

/**
 * Normalize processor, scaling data
 */
public class NormalizeModelProcessor extends BasicModelProcessor implements Processor {

    private final static Logger log = LoggerFactory.getLogger(NormalizeModelProcessor.class);

    private boolean isToShuffleData = false;

    public NormalizeModelProcessor() {
        this(false);
    }

    public NormalizeModelProcessor(boolean isToShuffleData) {
        this.isToShuffleData = isToShuffleData;
    }

    /**
     * runner for normalization data
     */
    @Override
    public int run() throws Exception {
        log.info("Step Start: normalize");
        long start = System.currentTimeMillis();
        try {
            setUp(ModelStep.NORMALIZE);
            syncDataToHdfs(modelConfig.getDataSet().getSource());

            switch(modelConfig.getBasic().getRunMode()) {
                case DIST:
                case MAPRED:
                    runPigNormalize();

                    if(isCorrOn()) {
                        runCorrMapReduceJob();
                        saveColumnConfigList();
                    }

                    if(this.isToShuffleData) {
                        // shuffling normalized data, to make data random
                        MapReduceShuffle shuffler = new MapReduceShuffle(this.modelConfig);
                        shuffler.run(this.pathFinder.getNormalizedDataPath());
                    }
                    break;
                case LOCAL:
                    runAkkaNormalize();
                    break;
            }

            syncDataToHdfs(modelConfig.getDataSet().getSource());
            clearUp(ModelStep.NORMALIZE);
        } catch (Exception e) {
            log.error("Error:", e);
            return -1;
        }
        log.info("Step Finished: normalize with {} ms", (System.currentTimeMillis() - start));
        return 0;
    }

    // OptionsParser doesn't to support *.jar currently.
    private String addRuntimeJars() {
        List<String> jars = new ArrayList<String>(16);
        // common-codec
        jars.add(JarManager.findContainingJar(Base64.class));
        // commons-compress-*.jar
        jars.add(JarManager.findContainingJar(BZip2CompressorInputStream.class));
        // commons-lang-*.jar
        jars.add(JarManager.findContainingJar(StringUtils.class));
        // common-io-*.jar
        jars.add(JarManager.findContainingJar(org.apache.commons.io.IOUtils.class));
        // common-collections
        jars.add(JarManager.findContainingJar(Predicate.class));
        // guava-*.jar
        jars.add(JarManager.findContainingJar(Splitter.class));
        // guagua-core-*.jar
        jars.add(JarManager.findContainingJar(NumberFormatUtils.class));
        // shifu-*.jar
        jars.add(JarManager.findContainingJar(getClass()));
        // jexl-*.jar
        jars.add(JarManager.findContainingJar(JexlException.class));
        // encog-core-*.jar
        jars.add(JarManager.findContainingJar(MLDataSet.class));
        // jackson-databind-*.jar
        jars.add(JarManager.findContainingJar(ObjectMapper.class));
        // jackson-core-*.jar
        jars.add(JarManager.findContainingJar(JsonParser.class));
        // jackson-annotations-*.jar
        jars.add(JarManager.findContainingJar(JsonIgnore.class));

        return StringUtils.join(jars, NNConstants.LIB_JAR_SEPARATOR);
    }

    private void runCorrMapReduceJob() throws IOException, InterruptedException, ClassNotFoundException {
        SourceType source = this.modelConfig.getDataSet().getSource();
        Configuration conf = new Configuration();

        // add jars to hadoop mapper and reducer
        new GenericOptionsParser(conf, new String[] { "-libjars", addRuntimeJars() });

        conf.setBoolean(GuaguaMapReduceConstants.MAPRED_MAP_TASKS_SPECULATIVE_EXECUTION, true);
        conf.setBoolean(GuaguaMapReduceConstants.MAPRED_REDUCE_TASKS_SPECULATIVE_EXECUTION, true);
        conf.set(NNConstants.MAPRED_JOB_QUEUE_NAME, Environment.getProperty(Environment.HADOOP_JOB_QUEUE, "default"));
        conf.setInt(GuaguaMapReduceConstants.MAPREDUCE_JOB_MAX_SPLIT_LOCATIONS, 5000);
        conf.set(
                Constants.SHIFU_MODEL_CONFIG,
                ShifuFileUtils.getFileSystemBySourceType(source)
                        .makeQualified(new Path(super.getPathFinder().getModelConfigPath(source))).toString());
        conf.set(
                Constants.SHIFU_COLUMN_CONFIG,
                ShifuFileUtils.getFileSystemBySourceType(source)
                        .makeQualified(new Path(super.getPathFinder().getColumnConfigPath(source))).toString());
        conf.set(Constants.SHIFU_MODELSET_SOURCE_TYPE, source.toString());

        conf.set("mapred.reduce.slowstart.completed.maps",
                Environment.getProperty("mapred.reduce.slowstart.completed.maps", "0.9"));

        String hdpVersion = HDPUtils.getHdpVersionForHDP224();
        if(StringUtils.isNotBlank(hdpVersion)) {
            // for hdp 2.2.4, hdp.version should be set and configuration files should be add to container class path
            conf.set("hdp.version", hdpVersion);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("hdfs-site.xml"), conf);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("core-site.xml"), conf);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("mapred-site.xml"), conf);
            HDPUtils.addFileToClassPath(HDPUtils.findContainingFile("yarn-site.xml"), conf);
        }

        conf.setBoolean(CombineInputFormat.SHIFU_VS_SPLIT_COMBINABLE, true);
        conf.setBoolean("mapreduce.input.fileinputformat.input.dir.recursive", true);

        // one can set guagua conf in shifuconfig
        for(Map.Entry<Object, Object> entry: Environment.getProperties().entrySet()) {
            if(CommonUtils.isHadoopConfigurationInjected(entry.getKey().toString())) {
                conf.set(entry.getKey().toString(), entry.getValue().toString());
            }
        }

        @SuppressWarnings("deprecation")
        Job job = new Job(conf, "Shifu: Correlation Computing Job : " + this.modelConfig.getModelSetName());
        job.setJarByClass(getClass());

        boolean isMultipleThreading = Boolean.TRUE.toString().equalsIgnoreCase(
                Environment.getProperty(Constants.SHIFU_CORRELATION_MULTI, "true"));
        if(isMultipleThreading) {
            job.setMapperClass(MultithreadedMapper.class);
            MultithreadedMapper.setMapperClass(job, CorrelationMapper.class);
            int threads;
            try {
                threads = Integer.parseInt(Environment.getProperty(Constants.SHIFU_CORRELATION_MULTI_THREADS, "6"));
            } catch (Exception e) {
                log.warn("'shifu.correlation.multi.threads' should be a int value, set default value: {}", 6);
                threads = 6;
            }
            MultithreadedMapper.setNumberOfThreads(job, threads);
        } else {
            job.setMapperClass(CorrelationMapper.class);
        }

        job.setMapOutputKeyClass(IntWritable.class);
        job.setMapOutputValueClass(CorrelationWritable.class);

        job.setInputFormatClass(CombineInputFormat.class);
        switch(modelConfig.getNormalize().getCorrelation()) {
            case NormPearson:
                FileInputFormat.setInputPaths(
                        job,
                        ShifuFileUtils.getFileSystemBySourceType(source).makeQualified(
                                new Path(super.getPathFinder().getNormalizedDataPath())));
                break;
            case Pearson:
            default:
                FileInputFormat.setInputPaths(
                        job,
                        ShifuFileUtils.getFileSystemBySourceType(source).makeQualified(
                                new Path(super.modelConfig.getDataSetRawPath())));
                break;
        }

        job.setReducerClass(CorrelationReducer.class);
        job.setNumReduceTasks(1);
        job.setOutputKeyClass(IntWritable.class);
        job.setOutputValueClass(Text.class);
        job.setOutputFormatClass(TextOutputFormat.class);

        String corrPath = super.getPathFinder().getCorrelationPath(source);
        FileOutputFormat.setOutputPath(job, new Path(corrPath));

        // clean output firstly
        ShifuFileUtils.deleteFile(corrPath, source);

        // submit job
        if(job.waitForCompletion(true)) {
            setCorrelationResultToColumnConfigList(source, corrPath);
        } else {
            throw new RuntimeException("MapReduce Correlation Computing Job failed.");
        }
    }

    private void setCorrelationResultToColumnConfigList(SourceType source, String corrPath) throws IOException {
        String outputFilePattern = corrPath + Path.SEPARATOR + "part-*";
        if(!ShifuFileUtils.isFileExists(outputFilePattern, source)) {
            throw new RuntimeException("Correlation computing output file not exist.");
        }

        List<Scanner> scanners = null;
        try {
            // here only works for 1 reducer
            FileStatus[] globStatus = ShifuFileUtils.getFileSystemBySourceType(source).globStatus(
                    new Path(outputFilePattern));
            if(globStatus == null || globStatus.length == 0) {
                throw new RuntimeException("Correlation computing output file not exist.");
            }
            scanners = ShifuFileUtils.getDataScanners(globStatus[0].getPath().toString(), source);
            Scanner scanner = scanners.get(0);
            String str = null;
            while(scanner.hasNext()) {
                str = scanner.nextLine().trim();
                if(str.contains(Constants.TAB_STR)) {
                    String[] splits = str.split(Constants.TAB_STR);
                    String corrStr = splits[1];
                    List<Double> dValues = getDoubleArray(CommonUtils.split(corrStr.substring(1, corrStr.length() - 1),
                            ","));
                    super.columnConfigList.get(Integer.valueOf(splits[0])).setCorrArray(dValues);
                }
            }
        } finally {
            if(scanners != null) {
                for(Scanner scanner: scanners) {
                    if(scanner != null) {
                        scanner.close();
                    }
                }
            }
        }
    }

    private List<Double> getDoubleArray(String[] units) {
        List<Double> dValues = new ArrayList<Double>(units.length);
        for(int i = 0; i < units.length; i++) {
            dValues.add(NumberFormatUtils.getDouble(units[i].trim(), 0d));
        }
        return dValues;
    }

    /**
     * Check if correlation computing is enabled
     */
    private boolean isCorrOn() {
        boolean isZScore = super.modelConfig.getNormalize().getNormType() == NormType.ZSCALE
                || super.modelConfig.getNormalize().getNormType() == NormType.ZSCORE
                || super.modelConfig.getNormalize().getNormType() == NormType.OLD_ZSCALE
                || super.modelConfig.getNormalize().getNormType() == NormType.OLD_ZSCORE;
        // NormPearson only works with zscore, while pearson works for all norm mode
        return (super.modelConfig.isMapReduceRunMode() && isZScore && super.modelConfig.getNormalize().getCorrelation() == Correlation.NormPearson)
                || (super.modelConfig.isMapReduceRunMode() && super.modelConfig.getNormalize().getCorrelation() == Correlation.Pearson);
    }

    /**
     * running akka normalize process
     * 
     * @throws IOException
     */
    private void runAkkaNormalize() throws IOException {
        SourceType sourceType = modelConfig.getDataSet().getSource();

        ShifuFileUtils.deleteFile(pathFinder.getNormalizedDataPath(), sourceType);
        ShifuFileUtils.deleteFile(pathFinder.getSelectedRawDataPath(), sourceType);

        List<Scanner> scanners = null;
        try {
            scanners = ShifuFileUtils.getDataScanners(
                    ShifuFileUtils.expandPath(modelConfig.getDataSetRawPath(), sourceType), sourceType);
        } catch (IOException e) {
            throw new ShifuException(ShifuErrorCode.ERROR_INPUT_NOT_FOUND, e, ", could not get input files "
                    + modelConfig.getDataSetRawPath());
        }

        if(scanners == null || scanners.size() == 0) {
            throw new ShifuException(ShifuErrorCode.ERROR_INPUT_NOT_FOUND, ", please check the data in "
                    + modelConfig.getDataSetRawPath() + " in " + sourceType);
        }

        AkkaSystemExecutor.getExecutor().submitNormalizeJob(modelConfig, columnConfigList, scanners);

        // release
        closeScanners(scanners);
    }

    /**
     * running pig normalize process
     * 
     * @throws IOException
     */
    @SuppressWarnings("deprecation")
    private void runPigNormalize() throws IOException {
        SourceType sourceType = modelConfig.getDataSet().getSource();

        ShifuFileUtils.deleteFile(pathFinder.getNormalizedDataPath(), sourceType);
        ShifuFileUtils.deleteFile(pathFinder.getNormalizedValidationDataPath(), sourceType);
        ShifuFileUtils.deleteFile(pathFinder.getSelectedRawDataPath(), sourceType);

        Map<String, String> paramsMap = new HashMap<String, String>();
        paramsMap.put("sampleRate", modelConfig.getNormalizeSampleRate().toString());
        paramsMap.put("sampleNegOnly", ((Boolean) modelConfig.isNormalizeSampleNegOnly()).toString());
        paramsMap.put("delimiter", CommonUtils.escapePigString(modelConfig.getDataSetDelimiter()));

        try {
            String normPigPath = null;
            if(modelConfig.getNormalize().getIsParquet()) {
                if(modelConfig.getBasic().getPostTrainOn()) {
                    normPigPath = pathFinder.getScriptPath("scripts/NormalizeWithParquetAndPostTrain.pig");
                } else {
                    log.info("Post train is disabled by 'postTrainOn=false'.");
                    normPigPath = pathFinder.getScriptPath("scripts/NormalizeWithParquet.pig");
                }
            } else {
                if(modelConfig.getBasic().getPostTrainOn()) {
                    // this condition is for comment, no matter post train enabled or not, only norm results will be
                    // stored since new post train solution
                }
                normPigPath = pathFinder.getScriptPath("scripts/Normalize.pig");
            }
            paramsMap.put(Constants.IS_COMPRESS, "true");
            paramsMap.put(Constants.IS_NORM_FOR_CLEAN, "false");
            PigExecutor.getExecutor().submitJob(modelConfig, normPigPath, paramsMap);

            Iterator<JobStats> iter = PigStats.get().getJobGraph().iterator();

            while(iter.hasNext()) {
                JobStats jobStats = iter.next();
                if(jobStats.getHadoopCounters() != null
                        && jobStats.getHadoopCounters().getGroup(Constants.SHIFU_GROUP_COUNTER) != null) {
                    long totalValidCount = jobStats.getHadoopCounters().getGroup(Constants.SHIFU_GROUP_COUNTER)
                            .getCounter("TOTAL_VALID_COUNT");
                    // If no basic record counter, check next one
                    if(totalValidCount == 0L) {
                        continue;
                    }
                    long invalidTagCount = jobStats.getHadoopCounters().getGroup(Constants.SHIFU_GROUP_COUNTER)
                            .getCounter("INVALID_TAG");

                    log.info("Total valid records {} after filtering, invalid tag records {}.", totalValidCount,
                            invalidTagCount);

                    if(totalValidCount > 0L && invalidTagCount * 1d / totalValidCount >= 0.8d) {
                        log.error("Too many invalid tags, please check you configuration on positive tags and negative tags.");
                    }
                }
                // only one pig job with such counters, break
                break;
            }

            if(StringUtils.isNotBlank(modelConfig.getValidationDataSetRawPath())) {
                paramsMap.put(Constants.IS_COMPRESS, "false");
                paramsMap.put(Constants.PATH_RAW_DATA, modelConfig.getValidationDataSetRawPath());
                paramsMap.put(Constants.PATH_NORMALIZED_DATA, pathFinder.getNormalizedValidationDataPath());
                PigExecutor.getExecutor().submitJob(modelConfig, normPigPath, paramsMap);
            }
        } catch (IOException e) {
            throw new ShifuException(ShifuErrorCode.ERROR_RUNNING_PIG_JOB, e);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

}
