package com.shareyi.molicode.service.gencode.impl

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.shareyi.fileutil.FileUtil
import com.shareyi.molicode.common.chain.HandlerChainFactoryImpl
import com.shareyi.molicode.common.chain.handler.awares.TableModelHandlerAware
import com.shareyi.molicode.common.constants.CommonConstant
import com.shareyi.molicode.common.constants.ConfigKeyConstant
import com.shareyi.molicode.common.enums.DataTypeEnum
import com.shareyi.molicode.common.utils.LogHelper
import com.shareyi.molicode.common.utils.Profiles
import com.shareyi.molicode.common.utils.SystemFileUtils
import com.shareyi.molicode.common.utils.TableNameUtil
import com.shareyi.molicode.common.valid.Validate
import com.shareyi.molicode.common.vo.code.SimpleTableInfoVo
import com.shareyi.molicode.common.vo.code.TableModelVo
import com.shareyi.molicode.common.vo.page.TableModelPageVo
import com.shareyi.molicode.common.web.CommonResult
import com.shareyi.molicode.context.TableModelContext
import com.shareyi.molicode.service.conf.AcConfigService
import com.shareyi.molicode.service.gencode.DatabaseTableService
import groovy.sql.Sql
import org.apache.commons.collections4.CollectionUtils
import org.apache.commons.collections4.MapUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.springframework.stereotype.Service

import javax.annotation.Resource
import java.sql.DatabaseMetaData

@Service("databaseTableService")
class DatabaseTableServiceImpl implements DatabaseTableService {


    @Resource
    AcConfigService acConfigService;


    CommonResult generateTableModel(TableModelPageVo tableModelPageVo) {
        CommonResult<String> result = CommonResult.create();
        try {
            def context = TableModelContext.create(tableModelPageVo)
            HandlerChainFactoryImpl.executeByHandlerAware(TableModelHandlerAware.class, context);
            result.succeed(context.outputPath);
        } catch (Exception e) {
            LogHelper.EXCEPTION.error("生成表模型失败，tableModelPageVo={}", tableModelPageVo, e);
            result.failed("生成表模型失败, 表:" + tableModelPageVo.tableName + ", 原因是:" + e.getMessage());
        }
        return result;
    }

    @Override
    CommonResult<TableModelVo> getTableInfo(String projectKey, String tableName) {
        CommonResult<String> result = CommonResult.create();
        try {
            TableModelPageVo tableModelPageVo = new TableModelPageVo();
            tableModelPageVo.setTableName(tableName);
            tableModelPageVo.setProjectKey(projectKey);
            tableModelPageVo.setSmartFlag(CommonConstant.STD_YN_YES);
            tableModelPageVo.setTableModelDir(SystemFileUtils.getTableModelDir(projectKey))
            tableModelPageVo.setModelType(CommonConstant.MODEL_TYPE_JSON);
            def context = TableModelContext.create(tableModelPageVo)
            HandlerChainFactoryImpl.executeByHandlerAware(TableModelHandlerAware.class, context);
            result.addDefaultModel(context.getTableModelVo())
            result.succeed();
        } catch (Exception e) {
            LogHelper.EXCEPTION.error("生成表模型失败，tableName={}", tableName, e);
            result.failed("生成表模型失败, 表:" + tableName + ", 原因是:" + e.getMessage());
        }
        return result;
    }

    @Override
    CommonResult<Object> saveTableModel(String projectKey, String tableModelJson) {
        CommonResult<String> result = CommonResult.create();
        try {
            TableModelVo tableModelVo = JSON.parseObject(tableModelJson, TableModelVo.class);
            String tableModelDir = FileUtil.getRuntimeFilePath("tableModel/project_" + projectKey);
            String fullPath = FileUtil.contactPath(tableModelDir, tableModelVo.getTableDefine().dbTableName + ".json");
            File file = new File(fullPath);
            FileUtil.makeSureFileExsit(file);
            def newJson = JSON.toJSONString(tableModelVo, SerializerFeature.PrettyFormat, SerializerFeature.QuoteFieldNames, SerializerFeature.UseSingleQuotes, SerializerFeature.DisableCircularReferenceDetect);
            FileUtils.write(file, newJson, Profiles.instance.fileEncoding, false);
            result.addDefaultModel(file.getAbsolutePath());
            result.succeed();
        } catch (Exception e) {
            LogHelper.EXCEPTION.error("保存表模型失败，projectKey={}, json={}", projectKey, tableModelJson, e);
            result.failed("保存表模型失败, 原因是:" + e.getMessage());
        }
        return result;
    }

/**
 * 生成数据库表名的tableModel xml文件
 * @param tName 表名
 */
    CommonResult<List<SimpleTableInfoVo>> getTableList(String projectKey) {
        CommonResult result = CommonResult.create();
        Sql sql = null;
        try {
            Validate.notEmpty(projectKey, "projectKey不能为空")
            Map<String, Map<String, String>> configMap = acConfigService.getConfigMapByProjectKey(projectKey, DataTypeEnum.JSON);
            Map<String, String> databaseConfigMap = configMap.get(ConfigKeyConstant.DatabaseConfig.CONFIG_KEY);
            String driverName = MapUtils.getString(databaseConfigMap, ConfigKeyConstant.DatabaseConfig.DRIVER_CLASS);
            String url = MapUtils.getString(databaseConfigMap, ConfigKeyConstant.DatabaseConfig.URL);
            String username = MapUtils.getString(databaseConfigMap, ConfigKeyConstant.DatabaseConfig.USERNAME);
            String password = MapUtils.getString(databaseConfigMap, ConfigKeyConstant.DatabaseConfig.PASSWORD);
            Validate.notEmpty(driverName, "数据库驱动不能为空")
            Validate.notEmpty(url, "数据库url不能为空")
            Validate.notEmpty(username, "数据库userName不能为空")
            Validate.notEmpty(password, "数据库密码不能为空")

            sql = Sql.newInstance(url, username, password, driverName);
            TableNameUtil tableNameUtil = new TableNameUtil()
            def meta = sql.connection.metaData
            String[] s = new String[1];
            s[0] = "TABLE";

            def list = [];
            def tableInfo = meta.getTables(null, null, null, s);
            while (tableInfo.next()) {
                SimpleTableInfoVo tInfo = new SimpleTableInfoVo();
                String tableName = tableInfo.getString("TABLE_NAME");
                tInfo.tableName = tableName;
                tInfo.id = tableNameUtil.upperFirst(tableNameUtil.convertToBeanNames(tableName));
                tInfo.cnname = tInfo.id;
                list.add(tInfo)
            }

            LinkedHashMap commentMap = getTableCommentMap(sql, meta)

            list.each { tableInfoVo ->
                String comment = commentMap.get(tableInfoVo.tableName);
                if (StringUtils.isNotEmpty(comment)) {
                    tableInfoVo.cnname = comment;
                }
            }
            loadLocalConfig(projectKey, list);
            result.addDefaultModel(list)
            result.succeed()
        } catch (Exception e) {
            LogHelper.DEFAULT.error("获取数据库表列表异常", e)
            result.failed("获取数据库表列表异常， 原因是：" + e.getMessage())
        } finally {
            if (sql != null) {
                sql.close();
            }
        }
        return result;
    }

    private LinkedHashMap getTableCommentMap(Sql sql, DatabaseMetaData meta) {
        def commentMap = [:]
        try {
            sql.eachRow("Select table_name,table_comment from INFORMATION_SCHEMA.TABLES Where table_schema = ? ", [meta['database']], { row ->
                commentMap.put(row[0], row[1])
            });
        } catch (Exception e) {
            LogHelper.EXCEPTION.error("获取表注释失败", e);
        }
        commentMap
    }

    /**
     * 加载本地已经配置的信息
     * @param projectKey
     * @param list
     */
    void loadLocalConfig(String projectKey, List<SimpleTableInfoVo> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        String tableModelDir = SystemFileUtils.getTableModelDir(projectKey);
        list.each { simpleTableInfoVo ->
            try {
                String filePath = FileUtil.contactPath(tableModelDir, simpleTableInfoVo.tableName + ".json")
                File file = new File(filePath);
                if (file.exists()) {
                    String tableModelJson = FileUtils.readFileToString(file, Profiles.instance.fileEncoding);
                    TableModelVo tableModelVo = JSON.parseObject(tableModelJson, TableModelVo.class);
                    simpleTableInfoVo.setId(tableModelVo.tableDefine.id);
                    simpleTableInfoVo.setCnname(tableModelVo.tableDefine.cnname);
                }
            } catch (Exception e) {
                LogHelper.EXCEPTION.error("获取tableModel历史信息失败，table={}", simpleTableInfoVo.tableName, e);
            }
        }
    }
}
