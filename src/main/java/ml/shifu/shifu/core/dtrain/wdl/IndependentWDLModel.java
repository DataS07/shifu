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
package ml.shifu.shifu.core.dtrain.wdl;

import ml.shifu.shifu.container.obj.ModelNormalizeConf.NormType;
import ml.shifu.shifu.core.Normalizer;
import ml.shifu.shifu.core.dtrain.CommonConstants;
import ml.shifu.shifu.core.dtrain.StringUtils;
import ml.shifu.shifu.core.dtrain.nn.NNColumnStats;
import ml.shifu.shifu.util.BinUtils;
import ml.shifu.shifu.util.Constants;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * {@link IndependentWDLModel} is a light WDL engine to predict WDL model, the only dependency is shifu, guagua.
 *
 * <p>
 * {@link #compute(Map)} are the API called for prediction.
 *
 * <p>
 *
 * @author : Wu Devin (haifwu@paypal.com)
 */
public class IndependentWDLModel {

    /**
     * WideAndDeep graph definition network.
     */
    private WideAndDeep wnd;

    /**
     * Normalization type
     */
    private NormType normType;

    /**
     * Model version
     */
    private static int version = CommonConstants.WDL_FORMAT_VERSION;

    /**
     * Mapping for (ColumnNum, Map(Category, CategoryIndex) for categorical feature
     */
    private Map<Integer, Map<String, Integer>> cateIndexMap;
    /**
     * ZScore stddev cutoff value per each column
     */
    private Map<Integer, Double> cutOffMap;
    /**
     * Mapping for (ColumnNum, ColumnName)
     */
    private Map<Integer, String> numNameMap;
    /**
     * Mapping for (columnNum, binBoundaries) for numberical columns
     */
    private Map<Integer, List<Double>> numerBinBoundaries;
    /**
     * Mapping for (columnNum, woes) for numberical columns; for hybrid, woe bins for both numberical and categorical
     * bins; last one in weightedWoes is for missing value bin
     */
    private Map<Integer, List<Double>> numerWoes;
    /**
     * Mapping for (columnNum, wgtWoes) for numberical columns; for hybrid, woe bins for both numberical and categorical
     * bins; last one in weightedBinWoes is for missing value bin
     */
    private Map<Integer, List<Double>> numerWgtWoes;
    /**
     * Mapping for (columnNum, mean) for all columns
     */
    private Map<Integer, Double> numerMeanMap;
    /**
     * Mapping for (columnNum, stddev) for all columns
     */
    private Map<Integer, Double> numerStddevMap;
    /**
     * Mapping for (columnNum, woeMean) for all columns
     */
    private Map<Integer, Double> woeMeanMap;
    /**
     * Mapping for (columnNum, woeStddev) for all columns
     */
    private Map<Integer, Double> woeStddevMap;
    /**
     * Mapping for (columnNum, weightedWoeMean) for all columns
     */
    private Map<Integer, Double> wgtWoeMeanMap;
    /**
     * Mapping for (columnNum, weightedWoeStddev) for all columns
     */
    private Map<Integer, Double> wgtWoeStddevMap;

    private IndependentWDLModel(WideAndDeep wideAndDeep, NormType normType, Map<Integer, Double> cutOffMap,
                                Map<Integer, String> numNameMap, Map<Integer, Map<String, Integer>> cateIndexMap,
                                Map<Integer, List<Double>> numerBinBoundaries, Map<Integer, List<Double>> numerWoes,
                                Map<Integer, List<Double>> numerWgtWoes, Map<Integer, Double> numerMeanMap,
                                Map<Integer, Double> numerStddevMap, Map<Integer, Double> woeMeanMap, Map<Integer, Double> woeStddevMap,
                                Map<Integer, Double> wgtWoeMeanMap, Map<Integer, Double> wgtWoeStddevMap) {
        this.wnd = wideAndDeep;
        this.normType = normType;
        this.cutOffMap = cutOffMap;
        this.numNameMap = numNameMap;
        this.cateIndexMap = cateIndexMap;
        this.numerBinBoundaries = numerBinBoundaries;
        this.numerWoes = numerWoes;
        this.numerWgtWoes = numerWgtWoes;
        this.numerMeanMap = numerMeanMap;
        this.numerStddevMap = numerStddevMap;
        this.woeMeanMap = woeMeanMap;
        this.woeStddevMap = woeStddevMap;
        this.wgtWoeMeanMap = wgtWoeMeanMap;
        this.wgtWoeStddevMap = wgtWoeStddevMap;
    }

    /**
     * Compute logits according to data inputs
     *
     * @param denseInputs,
     *            the dense inputs for deep model, numerical values
     * @param embedInputs,
     *            the embed inputs for deep model, category values
     * @param wideInputs,
     *            the wide model inputs, category values
     * @return model score of the inputs.
     */
    public float[] compute(float[] denseInputs, List<SparseInput> embedInputs, List<SparseInput> wideInputs) {
        return this.wnd.forward(denseInputs, embedInputs, wideInputs);
    }

    /**
     * Given {@code dataMap} with format (columnName, value), compute score values of wide and deep model.
     *
     * <p>
     * No any alert or exception if your {@code dataMap} doesn't contain features included in the model, such case will
     * be treated as missing value case. Please make sure feature names in keys of {@code dataMap} are consistent with
     * names in model.
     *
     * <p>
     * In {@code dataMap}, numberical value can be (String, Float) format or (String, String) format, they will all be
     * parsed to Float; categorical value are all converted to (String, String). If value not in our categorical list,
     * it will also be treated as missing value.
     *
     * <p>
     * In {@code dataMap}, data should be raw value and normalization is computed inside according to {@link #normType}
     * and stats information in such model.
     *
     * @param dataMap
     *            {@code dataMap} for (columnName, value), numberic value can be float/String, categorical feature can
     *            be int(index) or category value. if not set or set to null, such feature will be treated as missing
     *            value. For numberical value, if it cannot be parsed successfully, it will also be treated as missing.
     * @return score output for wide and deep model
     */
    public float[] compute(Map<String, Object> dataMap) {
        return compute(getDenseInputs(dataMap), getEmbedInputs(dataMap), getWideInputs(dataMap));
    }

    /**
     * Load model instance from input stream which is saved in WDLOutput for specified binary format.
     *
     * @param input the input stream, flat input stream or gzip input stream both OK
     * @return the nn model instance
     * @throws IOException any IOException in de-serialization.
     */
    public static IndependentWDLModel loadFromStream(InputStream input) throws IOException {
        return loadFromStream(input, true);
    }

    /**
     * Load model instance from input stream which is saved in WDLOutput for specified binary format.
     *
     * @param input              the input stream, flat input stream or gzip input stream both OK
     * @param isRemoveNameSpace, is remove name space or not
     * @return the WideAndDeep model instance
     * @throws IOException any IOException in de-serialization.
     */

    public static IndependentWDLModel loadFromStream(InputStream input, boolean isRemoveNameSpace) throws IOException {
        DataInputStream dis;
        // check if gzip or not
        try {
            byte[] header = new byte[2];
            BufferedInputStream bis = new BufferedInputStream(input);
            bis.mark(2);
            int result = bis.read(header);
            bis.reset();
            int ss = (header[0] & 0xff) | ((header[1] & 0xff) << 8);
            if(result != -1 && ss == GZIPInputStream.GZIP_MAGIC) {
                dis = new DataInputStream(new GZIPInputStream(bis));
            } else {
                dis = new DataInputStream(bis);
            }
        } catch (java.io.IOException e) {
            dis = new DataInputStream(input);
        }

        int version = dis.readInt();
        IndependentWDLModel.setVersion(version);
        // Reserved two float field, one double field and one string field
        dis.readFloat();
        dis.readFloat();
        dis.readDouble();
        dis.readUTF();

        // read normStr
        String normStr = StringUtils.readString(dis);
        NormType normType = NormType.valueOf(normStr != null ? normStr.toUpperCase() : null);

        int columnSize = dis.readInt();
        // for all features
        Map<Integer, String> numNameMap = new HashMap<>(columnSize);
        // for numerical features
        Map<Integer, List<Double>> numerBinBoundaries = new HashMap<>(columnSize);
        Map<Integer, List<Double>> numerWoes = new HashMap<>(columnSize);
        Map<Integer, List<Double>> numerWgtWoes = new HashMap<>(columnSize);
        // for all features
        Map<Integer, Double> numerMeanMap = new HashMap<>(columnSize);
        Map<Integer, Double> numerStddevMap = new HashMap<>(columnSize);
        Map<Integer, Double> woeMeanMap = new HashMap<>(columnSize);
        Map<Integer, Double> woeStddevMap = new HashMap<>(columnSize);
        Map<Integer, Double> wgtWoeMeanMap = new HashMap<>(columnSize);
        Map<Integer, Double> wgtWoeStddevMap = new HashMap<>(columnSize);
        Map<Integer, Double> cutoffMap = new HashMap<>(columnSize);
        Map<Integer, Map<String, Integer>> cateIndexMapping = new HashMap<>(columnSize);
        for(int i = 0; i < columnSize; i++) {
            NNColumnStats cs = new NNColumnStats();
            cs.readFields(dis);

            List<Double> binWoes = cs.getBinCountWoes();
            List<Double> binWgtWoes = cs.getBinWeightWoes();
            int columnNum = cs.getColumnNum();

            if(isRemoveNameSpace) {
                // remove name-space in column name to make it be called by simple name
                numNameMap.put(columnNum, StringUtils.getSimpleColumnName(cs.getColumnName()));
            } else {
                numNameMap.put(columnNum, cs.getColumnName());
            }

            // for categorical features
            Map<String, Integer> cateIndexMap = new HashMap<>(cs.getBinCategories().size());

            if(cs.isCategorical() || cs.isHybrid()) {
                List<String> binCategories = cs.getBinCategories();

                for(int j = 0; j < binCategories.size(); j++) {
                    String currCate = binCategories.get(j);
                    if(currCate.contains(Constants.CATEGORICAL_GROUP_VAL_DELIMITER)) {
                        // merged category should be flatten, use own split function to avoid depending on guava jar in
                        // prediction
                        String[] splits = StringUtils.split(currCate, Constants.CATEGORICAL_GROUP_VAL_DELIMITER);
                        for(String str : splits) {
                            cateIndexMap.put(str, j);
                        }
                    } else {
                        cateIndexMap.put(currCate, j);
                    }
                }
            }

            if(cs.isNumerical() || cs.isHybrid()) {
                numerBinBoundaries.put(columnNum, cs.getBinBoundaries());
                numerWoes.put(columnNum, binWoes);
                numerWgtWoes.put(columnNum, binWgtWoes);
            }

            cateIndexMapping.put(columnNum, cateIndexMap);
            numerMeanMap.put(columnNum, cs.getMean());
            numerStddevMap.put(columnNum, cs.getStddev());
            woeMeanMap.put(columnNum, cs.getWoeMean());
            woeStddevMap.put(columnNum, cs.getWoeStddev());
            wgtWoeMeanMap.put(columnNum, cs.getWoeWgtMean());
            wgtWoeStddevMap.put(columnNum, cs.getWoeWgtStddev());
            cutoffMap.put(columnNum, cs.getCutoff());
        }

        WideAndDeep wideAndDeep = new WideAndDeep();
        wideAndDeep.readFields(dis);
        return new IndependentWDLModel(wideAndDeep, normType, cutoffMap, numNameMap, cateIndexMapping,
                numerBinBoundaries, numerWoes, numerWgtWoes, numerMeanMap, numerStddevMap, woeMeanMap, woeStddevMap,
                wgtWoeMeanMap, wgtWoeStddevMap);
    }

    /**
     * @return the version
     */
    public static int getVersion() {
        return IndependentWDLModel.version;
    }

    /**
     * @param version the version to set
     */
    public static void setVersion(int version) {
        IndependentWDLModel.version = version;
    }

    private List<SparseInput> getEmbedInputs(Map<String, Object> dataMap) {
        List<SparseInput> embedInputs = new ArrayList<>();
        Object value;
        for(Integer columnId : this.wnd.getEmbedColumnIds()) {
            value = getValueByColumnId(columnId, dataMap);
            if(value != null) {
                embedInputs.add(new SparseInput(columnId, getValueIndex(columnId, value.toString())));
            } else {
                // when the value missing
                embedInputs.add(new SparseInput(columnId, getMissingTypeCategory(columnId)));
            }
        }
        return embedInputs;
    }

    private int getMissingTypeCategory(int columnId) {
        //TODO if this right? Current return +1 of the last index
        return this.cateIndexMap.get(columnId).values().size();
    }

    private float[] getDenseInputs(Map<String, Object> dataMap) {
        // Get dense inputs
        float[] numericalValues = new float[this.wnd.getDenseColumnIds().size()];
        Object value;
        for(int i = 0; i < this.wnd.getDenseColumnIds().size(); i++) {
            value = getValueByColumnId(this.wnd.getDenseColumnIds().get(i), dataMap);
            if(value != null) {
                numericalValues[i] = normalize(this.wnd.getDenseColumnIds().get(i), value, this.normType);
            } else {
                numericalValues[i] = getMissingNumericalValue(this.wnd.getDenseColumnIds().get(i));
            }
        }
        return numericalValues;
    }

    private int getValueIndex(int columnId, String value) {
        return this.cateIndexMap.get(columnId).get(value);
    }

    private List<SparseInput> getWideInputs(Map<String, Object> dataMap) {
        List<SparseInput> wideInputs = new ArrayList<>();
        Object value;
        for(Integer columnId : this.wnd.getWideColumnIds()) {
            value = getValueByColumnId(columnId, dataMap);
            if(value != null) {
                wideInputs.add(new SparseInput(columnId, getValueIndex(columnId, value.toString())));
            } else {
                // when the value missing
                wideInputs.add(new SparseInput(columnId, getMissingTypeCategory(columnId)));
            }
        }
        return wideInputs;
    }

    private float normalize(int columnNum, Object obj, NormType normType) {
        float value;
        // numberical column
        switch(this.normType) {
            case WOE:
                value = getNumericalWoeValue(columnNum, obj, false);
                break;
            case WEIGHT_WOE:
                value = getNumericalWoeValue(columnNum, obj, true);
                break;
            case WOE_ZSCORE:
            case WOE_ZSCALE:
                value = getNumericalWoeZScoreValue(columnNum, obj, false);
                break;
            case WEIGHT_WOE_ZSCORE:
            case WEIGHT_WOE_ZSCALE:
                value = getNumericalWoeZScoreValue(columnNum, obj, true);
                break;
            case OLD_ZSCALE:
            case OLD_ZSCORE:
            case ZSCALE:
            case ZSCORE:
            case HYBRID:
            case WEIGHT_HYBRID:
            default:
                value = getNumericalZScoreValue(columnNum, obj);
                break;
        }
        return value;
    }

    private Object getValueByColumnId(int columnId, Map<String, Object> dataMap) {
        return dataMap.get(this.numNameMap.get(columnId));
    }

    private float getMissingNumericalValue(int columnId) {
        return defaultMissingValue(this.numerMeanMap.get(columnId));
    }

    private float defaultMissingValue(Double mean) {
        Double defaultValue = mean == null ? 0 : mean;
        return defaultValue.floatValue();
    }

    private float getNumericalWoeValue(Integer columnNum, Object obj, boolean isWeighted) {
        int binIndex = -1;
        if(obj != null) {
            binIndex = BinUtils.getNumericalBinIndex(this.numerBinBoundaries.get(columnNum), obj.toString());
        }
        List<Double> binWoes = isWeighted ? this.numerWgtWoes.get(columnNum) : this.numerWoes.get(columnNum);

        Double value;
        if(binIndex == -1) {
            // The last bin in woeBins is the miss value bin.
            value = binWoes.get(binWoes.size() - 1);
        } else {
            value = binWoes.get(binIndex);
        }
        return value.floatValue();
    }

    private float getNumericalWoeZScoreValue(Integer columnNum, Object obj, boolean isWeighted) {
        float woe = getNumericalWoeValue(columnNum, obj, isWeighted);
        Map<Integer, Double> woeMeans = isWeighted ? this.wgtWoeMeanMap : this.woeMeanMap;
        Map<Integer, Double> woeStddevs = isWeighted ? this.wgtWoeStddevMap : this.woeStddevMap;
        double mean = woeMeans.get(columnNum), stddev = woeStddevs.get(columnNum);
        double realCutoff = Normalizer.checkCutOff(this.cutOffMap.get(columnNum));
        return Normalizer.computeZScore(woe, mean, stddev, realCutoff)[0].floatValue();
    }

    private float getNumericalZScoreValue(Integer columnNum, Object obj) {
        double mean = this.numerMeanMap.get(columnNum);
        double stddev = this.numerStddevMap.get(columnNum);
        float rawValue;
        if(obj == null || obj.toString().length() == 0) {
            rawValue = defaultMissingValue(mean);
        } else {
            try {
                rawValue = Float.parseFloat(obj.toString());
            } catch (Exception e) {
                rawValue = defaultMissingValue(mean);
            }
        }
        double realCutoff = Normalizer.checkCutOff(this.cutOffMap.get(columnNum));
        return Normalizer.computeZScore(rawValue, mean, stddev, realCutoff)[0].floatValue();
    }

    /**
     * @return the wnd
     */
    public WideAndDeep getWnd() {
        return wnd;
    }

}