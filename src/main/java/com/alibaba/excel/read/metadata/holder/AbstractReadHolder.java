package com.alibaba.excel.read.metadata.holder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.converters.Converter;
import com.alibaba.excel.converters.ConverterKeyBuild;
import com.alibaba.excel.converters.DefaultConverterLoader;
import com.alibaba.excel.enums.CellDataTypeEnum;
import com.alibaba.excel.enums.HeadKindEnum;
import com.alibaba.excel.enums.HolderEnum;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.exception.ExcelAnalysisException;
import com.alibaba.excel.exception.ExcelAnalysisStopException;
import com.alibaba.excel.exception.ExcelDataConvertException;
import com.alibaba.excel.metadata.AbstractHolder;
import com.alibaba.excel.metadata.CellData;
import com.alibaba.excel.metadata.Head;
import com.alibaba.excel.metadata.property.ExcelContentProperty;
import com.alibaba.excel.read.listener.ModelBuildEventListener;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.read.listener.ReadListenerRegistryCenter;
import com.alibaba.excel.read.listener.event.AnalysisFinishEvent;
import com.alibaba.excel.read.metadata.ReadBasicParameter;
import com.alibaba.excel.read.metadata.property.ExcelReadHeadProperty;
import com.alibaba.excel.util.StringUtils;

/**
 * Read Holder
 *
 * @author zhuangjiaju
 */
public abstract class AbstractReadHolder extends AbstractHolder implements ReadHolder, ReadListenerRegistryCenter {
    /**
     * Count the number of added heads when read sheet.
     *
     * <li>0 - This Sheet has no head ,since the first row are the data
     * <li>1 - This Sheet has one row head , this is the default
     * <li>2 - This Sheet has two row head ,since the third row is the data
     */
    private Integer headRowNumber;
    /**
     * Excel head property
     */
    private ExcelReadHeadProperty excelReadHeadProperty;
    /**
     * Read listener
     */
    private List<ReadListener> readListenerList;

    public AbstractReadHolder(ReadBasicParameter readBasicParameter, AbstractReadHolder parentAbstractReadHolder,
        Boolean convertAllFiled) {
        super(readBasicParameter, parentAbstractReadHolder);
        if (readBasicParameter.getUse1904windowing() == null && parentAbstractReadHolder != null) {
            getGlobalConfiguration()
                .setUse1904windowing(parentAbstractReadHolder.getGlobalConfiguration().getUse1904windowing());
        } else {
            getGlobalConfiguration().setUse1904windowing(readBasicParameter.getUse1904windowing());
        }

        // Initialization property
        this.excelReadHeadProperty = new ExcelReadHeadProperty(getClazz(), getHead(), convertAllFiled);
        if (readBasicParameter.getHeadRowNumber() == null) {
            if (parentAbstractReadHolder == null) {
                if (excelReadHeadProperty.hasHead()) {
                    this.headRowNumber = excelReadHeadProperty.getHeadRowNumber();
                } else {
                    this.headRowNumber = 1;
                }
            } else {
                this.headRowNumber = parentAbstractReadHolder.getHeadRowNumber();
            }
        } else {
            this.headRowNumber = readBasicParameter.getHeadRowNumber();
        }

        if (parentAbstractReadHolder == null) {
            this.readListenerList = new ArrayList<ReadListener>();
        } else {
            this.readListenerList = new ArrayList<ReadListener>(parentAbstractReadHolder.getReadListenerList());
        }
        if (HolderEnum.WORKBOOK.equals(holderType())) {
            readListenerList.add(new ModelBuildEventListener());
        }
        if (readBasicParameter.getCustomReadListenerList() != null
            && !readBasicParameter.getCustomReadListenerList().isEmpty()) {
            this.readListenerList.addAll(readBasicParameter.getCustomReadListenerList());
        }

        if (parentAbstractReadHolder == null) {
            setConverterMap(DefaultConverterLoader.loadDefaultReadConverter());
        } else {
            setConverterMap(new HashMap<String, Converter>(parentAbstractReadHolder.getConverterMap()));
        }
        if (readBasicParameter.getCustomConverterList() != null
            && !readBasicParameter.getCustomConverterList().isEmpty()) {
            for (Converter converter : readBasicParameter.getCustomConverterList()) {
                getConverterMap().put(
                    ConverterKeyBuild.buildKey(converter.supportJavaTypeKey(), converter.supportExcelTypeKey()),
                    converter);
            }
        }
    }

    @Override
    public void register(AnalysisEventListener listener) {
        readListenerList.add(listener);
    }

    @Override
    public void notifyEndOneRow(AnalysisFinishEvent event, AnalysisContext analysisContext) {
        Map<Integer, CellData> cellDataMap = event.getAnalysisResult();
        ReadRowHolder readRowHolder = analysisContext.readRowHolder();
        readRowHolder.setCurrentRowAnalysisResult(cellDataMap);

        if (readRowHolder.getRowIndex() >= analysisContext.readSheetHolder().getHeadRowNumber()) {
            for (ReadListener readListener : analysisContext.currentReadHolder().readListenerList()) {
                try {
                    readListener.invoke(readRowHolder.getCurrentRowAnalysisResult(), analysisContext);
                } catch (Exception e) {
                    for (ReadListener readListenerException : analysisContext.currentReadHolder().readListenerList()) {
                        try {
                            readListenerException.onException(e, analysisContext);
                        } catch (Exception exception) {
                            throw new ExcelAnalysisException("Listen error!", exception);
                        }
                    }
                }
                if (!readListener.hasNext(analysisContext)) {
                    throw new ExcelAnalysisStopException();
                }
            }
            return;
        }
        // Now is header
        if (analysisContext.readSheetHolder().getHeadRowNumber().equals(readRowHolder.getRowIndex() + 1)) {
            buildHead(analysisContext, cellDataMap);
        }
    }

    @Override
    public void notifyAfterAllAnalysed(AnalysisContext analysisContext) {
        for (ReadListener readListener : readListenerList) {
            readListener.doAfterAllAnalysed(analysisContext);
        }
    }

    private void buildHead(AnalysisContext analysisContext, Map<Integer, CellData> cellDataMap) {
        if (!HeadKindEnum.CLASS.equals(analysisContext.currentReadHolder().excelReadHeadProperty().getHeadKind())) {
            return;
        }
        Map<Integer, String> dataMap = buildStringMap(cellDataMap, analysisContext.currentReadHolder());
        ExcelReadHeadProperty excelHeadPropertyData = analysisContext.readSheetHolder().excelReadHeadProperty();
        Map<Integer, Head> headMapData = excelHeadPropertyData.getHeadMap();
        Map<Integer, ExcelContentProperty> contentPropertyMapData = excelHeadPropertyData.getContentPropertyMap();
        Map<Integer, Head> tmpHeadMap = new HashMap<Integer, Head>(headMapData.size() * 4 / 3 + 1);
        Map<Integer, ExcelContentProperty> tmpContentPropertyMap =
            new HashMap<Integer, ExcelContentProperty>(contentPropertyMapData.size() * 4 / 3 + 1);
        for (Map.Entry<Integer, Head> entry : headMapData.entrySet()) {
            Head headData = entry.getValue();
            if (headData.getForceIndex() || !headData.getForceName()) {
                tmpHeadMap.put(entry.getKey(), headData);
                tmpContentPropertyMap.put(entry.getKey(), contentPropertyMapData.get(entry.getKey()));
                continue;
            }
            String headName = headData.getHeadNameList().get(0);

            for (Map.Entry<Integer, String> stringEntry : dataMap.entrySet()) {
                String headString = stringEntry.getValue();
                Integer stringKey = stringEntry.getKey();
                if (StringUtils.isEmpty(headString)) {
                    continue;
                }
                if (analysisContext.currentReadHolder().globalConfiguration().getAutoTrim()) {
                    headString = headString.trim();
                }
                if (headName.equals(headString)) {
                    headData.setColumnIndex(stringKey);
                    tmpHeadMap.put(stringKey, headData);
                    tmpContentPropertyMap.put(stringKey, contentPropertyMapData.get(entry.getKey()));
                    break;
                }
            }
        }
        excelHeadPropertyData.setHeadMap(tmpHeadMap);
        excelHeadPropertyData.setContentPropertyMap(tmpContentPropertyMap);
    }

    private Map<Integer, String> buildStringMap(Map<Integer, CellData> cellDataMap, ReadHolder readHolder) {
        Map<Integer, String> stringMap = new HashMap<Integer, String>(cellDataMap.size() * 4 / 3 + 1);
        for (Map.Entry<Integer, CellData> entry : cellDataMap.entrySet()) {
            CellData cellData = entry.getValue();
            if (cellData.getType() == CellDataTypeEnum.EMPTY) {
                stringMap.put(entry.getKey(), null);
                continue;
            }
            Converter converter =
                readHolder.converterMap().get(ConverterKeyBuild.buildKey(String.class, cellData.getType()));
            if (converter == null) {
                throw new ExcelDataConvertException(
                    "Converter not found, convert " + cellData.getType() + " to String");
            }
            try {
                stringMap.put(entry.getKey(),
                    (String)(converter.convertToJavaData(cellData, null, readHolder.globalConfiguration())));
            } catch (Exception e) {
                throw new ExcelDataConvertException("Convert data " + cellData + " to String error ", e);
            }
        }
        return stringMap;
    }

    public List<ReadListener> getReadListenerList() {
        return readListenerList;
    }

    public void setReadListenerList(List<ReadListener> readListenerList) {
        this.readListenerList = readListenerList;
    }

    public ExcelReadHeadProperty getExcelReadHeadProperty() {
        return excelReadHeadProperty;
    }

    public void setExcelReadHeadProperty(ExcelReadHeadProperty excelReadHeadProperty) {
        this.excelReadHeadProperty = excelReadHeadProperty;
    }

    public Integer getHeadRowNumber() {
        return headRowNumber;
    }

    public void setHeadRowNumber(Integer headRowNumber) {
        this.headRowNumber = headRowNumber;
    }

    @Override
    public List<ReadListener> readListenerList() {
        return getReadListenerList();
    }

    @Override
    public ExcelReadHeadProperty excelReadHeadProperty() {
        return getExcelReadHeadProperty();
    }

}
