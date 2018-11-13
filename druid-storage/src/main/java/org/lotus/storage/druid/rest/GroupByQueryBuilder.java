package org.lotus.storage.druid.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by quanchengyun on 2018/5/11.
 */
public class GroupByQueryBuilder implements QueryBuilder{

    private Map query = new HashMap<>();

    private ObjectMapper json = new ObjectMapper();


    public static GroupByQueryBuilder builder() {
        GroupByQueryBuilder builder = new GroupByQueryBuilder();
        builder.json.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        builder.query.put("queryType","groupBy");
        return builder;
    }

    public GroupByQueryBuilder dataSource(String dataSource){
        query.put("dataSource",dataSource);
        return this;
    }

    // 简单的时间粒度
    public GroupByQueryBuilder granularity(Granularity granularity){
        query.put("granularity",granularity);
        return this;
    }

    /**
     *
     * @param ptTime  格式为 PT1H,PT1M,PT1S 时间粒度为 1 小时，1分钟，1秒 ，数字可为小数，整数
     * @param timeZone 返回的时间点时区   "Asia/Shanghai"
     * @param start 开始的时间点
     * @return
     */
    public GroupByQueryBuilder granularity(String ptTime ,String timeZone, DateTime start){
        Map m = new HashMap();
        m.put("period",ptTime);
        m.put("type","period");
        m.put("timezone",timeZone);
        m.put("start",start.toString("yyyy-MM-dd'T'HH:mm:ssZ"));
        query.put("granularity",m);
        return this;
    }

    /**
     *
     * @param timeInterval  时间粒度毫秒单位
     * @param start 时间点开始时间 UTC
     * @return
     */
    public GroupByQueryBuilder granularity(long  timeInterval , DateTime start){
        Map m = new HashMap();
        m.put("duration",timeInterval);
        m.put("type","duration");
        m.put("timezone","Asia/Shanghai");
        m.put("start",start.toString("yyyy-MM-dd'T'HH:mm:ssZ"));
        query.put("granularity",m);
        return this;
    }



    public GroupByQueryBuilder dimensions(String[] dimensions){
        query.put("dimensions",dimensions);
        return this;
    }

    public GroupByQueryBuilder limitSpec(LimitSpec limitSpec){
        query.put("limitSpec",limitSpec);
        return this;
    }

    public GroupByQueryBuilder filter(Filter filter){
        query.put("filter",filter);
        return this;
    }

    public GroupByQueryBuilder addAggregation(Aggregation aggregation){
        List<Aggregation> aggregations = query.get("aggregations")==null?null:(List<Aggregation>)query.get("aggregations");
        if(aggregations==null){
            aggregations = new ArrayList<>();
            query.put("aggregations",aggregations);
        }
        aggregations.add(aggregation);
        return this;
    }


    public GroupByQueryBuilder addPostAggregation(PostAggregation aggregation){
        List<PostAggregation> aggregations = query.get("postAggregations")==null?null:(List<PostAggregation>)query.get("postAggregations");
        if(aggregations==null){
            aggregations = new ArrayList<>();
            query.put("postAggregations",aggregations);
        }
        aggregations.add(aggregation);
        return this;
    }

    public GroupByQueryBuilder timeRange(TimeInterval intervals){
        query.put("intervals",intervals.format());
        return this;
    }

    public GroupByQueryBuilder having(Having having){
        query.put("having",having);
        return this;
    }


    /**
     * {
     "queryType": "groupBy",
     "dataSource": "sample_datasource",
     "granularity": "day",
     "dimensions": ["country", "device"],
     "limitSpec": { "type": "default", "limit": 5000, "columns": ["country", "data_transfer"] },
     "filter": {
     "type": "and",
     "fields": [
     { "type": "selector", "dimension": "carrier", "value": "AT&T" },
     { "type": "or",
     "fields": [
     { "type": "selector", "dimension": "make", "value": "Apple" },
     { "type": "selector", "dimension": "make", "value": "Samsung" }
     ]
     }
     ]
     },
     "aggregations": [
     { "type": "longSum", "name": "total_usage", "fieldName": "user_count" },
     { "type": "doubleSum", "name": "data_transfer", "fieldName": "data_transfer" }
     ],
     "postAggregations": [
     { "type": "arithmetic",
     "name": "avg_usage",
     "fn": "/",
     "fields": [
     { "type": "fieldAccess", "fieldName": "data_transfer" },
     { "type": "fieldAccess", "fieldName": "total_usage" }
     ]
     }
     ],
     "intervals": [ "2012-01-01T00:00:00.000/2012-01-03T00:00:00.000" ],
     "having": {
     "type": "greaterThan",
     "aggregation": "total_usage",
     "value": 100
     }
     }
     */

    @Override
    public String build() throws Exception{
        return json.writeValueAsString(query);
    }

}
