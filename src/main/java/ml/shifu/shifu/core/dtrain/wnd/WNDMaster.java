/*
 * Copyright [2013-2019] PayPal Software Foundation
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
package ml.shifu.shifu.core.dtrain.wnd;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ml.shifu.guagua.master.AbstractMasterComputable;
import ml.shifu.guagua.master.MasterContext;
import ml.shifu.shifu.container.obj.ColumnConfig;
import ml.shifu.shifu.container.obj.ModelConfig;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.core.dtrain.CommonConstants;
import ml.shifu.shifu.core.dtrain.DTrainUtils;
import ml.shifu.shifu.fs.ShifuFileUtils;
import ml.shifu.shifu.util.CommonUtils;

/**
 * {@link WNDMaster} is master logic in wide and deep implementation based on Guagua.
 * 
 * <p>
 * Like neural network implementation on Guagua. WNDMaster is used to aggregate worker gradients and update global wide
 * and deep model inside of current master {@link #wnd}.
 * 
 * <p>
 * Based on worker aggregated gradients and current model, by using different optimizer implementation like SGD, ADAM,
 * AdaGrad to do model updates.
 * 
 * <p>
 * At the first iteration, workre results are ignored because after master model initialization, workers training model
 * needs pulling lastest consistent model in worker while not to use on random model. In
 * {@link #doCompute(MasterContext)}, the first iteration is to just send back model to workers and in {@link WNDWorker}
 * the first iteration is just to return an empty parameters without usage in master.
 * 
 * <p>
 * For fault tolerance, {@link #wnd} needs to be recovered from existing model hdfs folder if continuous model training
 * enabled.
 * 
 * @author Zhang David (pengzhang@paypal.com)
 */
public class WNDMaster extends AbstractMasterComputable<WNDParams, WNDParams> {

    protected static final Logger LOG = LoggerFactory.getLogger(WNDMaster.class);

    /**
     * Model configuration loaded from configuration file.
     */
    private ModelConfig modelConfig;

    /**
     * Column configuration loaded from configuration file.
     */
    private List<ColumnConfig> columnConfigList;

    /**
     * If variables are selected, if not, select variables with good candidate.
     */
    private boolean isAfterVarSelect;

    /**
     * Learning rate
     */
    @SuppressWarnings("unused")
    private double learningRate;

    /**
     * Whether to enable continuous model training based on existing models.
     */
    private boolean isContinuousEnabled = false;

    /**
     * # of numerical inputs
     */
    private int numInputs;

    /**
     * Valid parameters from ModelConfig#train#params
     */
    private Map<String, Object> validParams;

    /**
     * Wide and Deep model object inside of master which is global model and each iteration should be sent back.
     */
    private WideAndDeep wnd;

    @SuppressWarnings({ "unchecked", "unused" })
    @Override
    public void init(MasterContext<WNDParams, WNDParams> context) {
        Properties props = context.getProps();
        try {
            SourceType sourceType = SourceType
                    .valueOf(props.getProperty(CommonConstants.MODELSET_SOURCE_TYPE, SourceType.HDFS.toString()));
            this.modelConfig = CommonUtils.loadModelConfig(props.getProperty(CommonConstants.SHIFU_MODEL_CONFIG),
                    sourceType);
            this.columnConfigList = CommonUtils
                    .loadColumnConfigList(props.getProperty(CommonConstants.SHIFU_COLUMN_CONFIG), sourceType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // this.trainerId = Integer.valueOf(context.getProps().getProperty(CommonConstants.SHIFU_TRAINER_ID, "0"));

        int[] inputOutputIndex = DTrainUtils.getNumericAndCategoricalInputAndOutputCounts(this.columnConfigList);
        this.numInputs = inputOutputIndex[0];
        // regression outputNodeCount is 1, binaryClassfication, it is 1, OneVsAll it is 1, Native classification it is
        // 1, with index of 0,1,2,3 denotes different classes
        this.isAfterVarSelect = (inputOutputIndex[3] == 1);
        this.validParams = this.modelConfig.getTrain().getParams();
        this.learningRate = Double.valueOf(validParams.get(CommonConstants.LEARNING_RATE).toString());

        this.isContinuousEnabled = Boolean.TRUE.toString()
                .equalsIgnoreCase(context.getProps().getProperty(CommonConstants.CONTINUOUS_TRAINING));

        // Build wide and deep graph
        List<Integer> embedColumnIds = (List<Integer>) this.validParams.get(CommonConstants.NUM_EMBED_COLUMN_IDS);
        Integer embedOutputs = (Integer) this.validParams.get(CommonConstants.NUM_EMBED_OUTPUTS);
        List<Integer> embedOutputList = new ArrayList<>();
        for(Integer cId: embedColumnIds) {
            embedOutputList.add(embedOutputs == null ? CommonConstants.DEFAULT_EMBEDING_OUTPUT : embedOutputs);
        }
        List<Integer> numericalIds = DTrainUtils.getNumericalIds(this.columnConfigList, isAfterVarSelect);
        List<Integer> wideColumnIds = DTrainUtils.getCategoricalIds(columnConfigList, isAfterVarSelect);
        Map<Integer, Integer> idBinCateSizeMap = DTrainUtils.getIdBinCategorySizeMap(columnConfigList);
        int numLayers = (Integer) this.validParams.get(CommonConstants.NUM_HIDDEN_LAYERS);
        List<String> actFunc = (List<String>) this.validParams.get(CommonConstants.ACTIVATION_FUNC);
        List<Integer> hiddenNodes = (List<Integer>) this.validParams.get(CommonConstants.NUM_HIDDEN_NODES);
        Float l2reg = (Float) this.validParams.get(CommonConstants.WND_L2_REG);
        this.wnd = new WideAndDeep(idBinCateSizeMap, numInputs, numericalIds, embedColumnIds, embedOutputList,
                wideColumnIds, hiddenNodes, actFunc, l2reg);
    }

    @Override
    public WNDParams doCompute(MasterContext<WNDParams, WNDParams> context) {
        if(context.isFirstIteration()) {
            // Fist iteration, no need take worker results and after master model initialization, global model should be
            // sent to workers for training.
            return initOrRecoverModelWeights(context);
        }

        // aggregate all worker gradients to one gradient object.
        WNDParams aggregation = aggregateWorkerGradients(context);

        // TODO optimizer, wnd object as current model weights, aggregation as current iteration gradients aggregation
        // gradients = aggregation.getWnd();
        // this.wnd -= this.learningRate * gradients;

        // construct master result which contains WideAndDeep current model weights
        WNDParams params = new WNDParams();
        params.setTrainCount(aggregation.getTrainCount());
        params.setValidationCount(aggregation.getValidationCount());
        params.setTrainError(aggregation.getTrainError());
        params.setValidationError(aggregation.getValidationError());
        params.setSerializationType(SerializationType.WEIGHTS);
        params.setWnd(this.wnd);
        return params;
    }

    private WNDParams aggregateWorkerGradients(MasterContext<WNDParams, WNDParams> context) {
        WNDParams aggregation = null;
        for(WNDParams params: context.getWorkerResults()) {
            if(aggregation == null) {
                aggregation = params;
            } else {
                aggregation.combine(params);
            }
        }
        return aggregation;
    }

    private WNDParams initOrRecoverModelWeights(MasterContext<WNDParams, WNDParams> context) {
        WNDParams params = new WNDParams();
        if(this.isContinuousEnabled) {
            Path modelPath = new Path(context.getProps().getProperty(CommonConstants.GUAGUA_OUTPUT));
            WideAndDeep existingModel = loadModel(modelPath);
            if(existingModel != null) {
                this.wnd.updateWeights(existingModel);
            } else {
                LOG.warn("Continuous training enabled but existing model load failed, do random initialization.");
                this.wnd.initWeights();
            }
        } else {
            this.wnd.initWeights();
        }
        params.setWnd(this.wnd); // weights from this.wnd
        return params;
    }

    private WideAndDeep loadModel(Path modelPath) {
        FileSystem fileSystem = ShifuFileUtils.getFileSystemBySourceType(SourceType.HDFS);
        InputStream inputStream = null;
        try {
            inputStream = fileSystem.open(modelPath);
            return IndependentWNDModel.loadFromStream(inputStream).getWnd();
        } catch (IOException e) {
            LOG.error("IOException happen when load WideAndDeep from HDFS", e);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
        return null;
    }

}