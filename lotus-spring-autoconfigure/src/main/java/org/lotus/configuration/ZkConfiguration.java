package org.lotus.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.lotus.storage.zk.ZKConfigurationProperties;
import org.lotus.storage.zk.ZkListener;
import org.lotus.storage.zk.ZkServiceDiscovery;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Created by quanchengyun on 2017/9/18.
 */
@Configuration
@EnableAutoConfiguration
@ConditionalOnProperty(prefix="druid.zk")
public class ZkConfiguration {

    @Bean
    @ConfigurationProperties(prefix="druid.zk",ignoreInvalidFields=true,exceptionIfInvalid=false)
    public ZKConfigurationProperties zkConfigurationProperties(){
        return  new ZKConfigurationProperties();
    }

    @Bean
    public CuratorFramework curatorFramework(ZKConfigurationProperties config){
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
        CuratorFramework zkClient = builder.connectString(config.getHost())
                .sessionTimeoutMs(config.getSessionTimeouts())
                .connectionTimeoutMs(config.getConnectTimeouts())
                .canBeReadOnly(false)
                .retryPolicy(new ExponentialBackoffRetry(config.getRetryConnectIntervalMs(), 100))
                .namespace(config.getNamespace())
                .defaultData(null)
                .build();
        zkClient.start();
        return zkClient;
    }

    @Bean
    public PathChildrenCache pathChildrenCache(CuratorFramework zkClient,ZKConfigurationProperties zkConfig) throws Exception{
        PathChildrenCache cache= new PathChildrenCache(zkClient, zkConfig.getPath(), true);
        cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
        return  cache;
    }

    @Bean
    @ConditionalOnMissingBean
    public PathChildrenCacheListener zkListener(ZkServiceDiscovery zkServiceDiscovery){
        ZkListener listener = new ZkListener();
        listener.setZkServiceDiscovery(zkServiceDiscovery);
        return listener;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper initJSONMapper(){
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ZkServiceDiscovery zkServiceDiscovery(ZKConfigurationProperties zkConfig,PathChildrenCache zkCache,ObjectMapper jsonMapper){
        ZkServiceDiscovery instance = new ZkServiceDiscovery();
        instance.setZkCache(zkCache);
        instance.setZkConfig(zkConfig);
        instance.setJsonMapper(jsonMapper);
        return instance;
    }

}
