package cn.itmtx.ddd.ezlink.domain.domainservice.cache;

import cn.itmtx.ddd.ezlink.domain.domainobject.UrlMapDO;
import cn.itmtx.ddd.ezlink.domain.domainservice.enums.CacheKeyEnum;
import cn.itmtx.ddd.ezlink.domain.gateway.UrlMapGateway;
import com.google.common.hash.BloomFilter;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Objects;

@Component
@Slf4j
public class UrlMapCacheManager {

    @Autowired
    private UrlMapGateway urlMapGateway;

    @Autowired
    @Qualifier("compressionCodeBloom")
    private BloomFilter<String> compressionCodeBloom;

    /**
     * 缓存预热阈值
     */
    @Value("${ezlink.cache.preload.threshold}")
    private Integer preloadThreshold;

    @Autowired


    private final StringRedisTemplate stringRedisTemplate;

    public UrlMapCacheManager(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 刷新 UrlMap 缓存
     * @param urlMapDO
     */
    public void refreshUrlMapCache(UrlMapDO urlMapDO) {
        if (Objects.isNull(urlMapDO)) {
            return ;
        }

        //Insert the object to the Redis
        HashOperations<String, String, String> hashOperations = stringRedisTemplate.opsForHash();
        // HashTable:ezlink:access:code:hash   Key: CompressionCode,  Value:  Json urlMapDO
        hashOperations.put(CacheKeyEnum.ACCESS_CODE_HASH.getKey(), urlMapDO.getCompressionCode(), new Gson().toJson(urlMapDO));
    }

    /**
     * 根据 compressionCode 从缓存中获取 UrlMapDO
     * 如果缓存中没获取到，就从数据库中获取
     * @param compressionCode
     * @return
     */
    public UrlMapDO loadUrlMapFromCache(String compressionCode) {
        // BloomFilter, 防止缓存穿透
        if (!compressionCodeBloom.mightContain(compressionCode)) {
            log.info("The compressionCode [{}] not in BloomFilter.", compressionCode);
            return null;
        }

        HashOperations<String, String, String> hashOperations = stringRedisTemplate.opsForHash();
        String hv = hashOperations.get(CacheKeyEnum.ACCESS_CODE_HASH.getKey(), compressionCode);
        //查不到就去查DB
        UrlMapDO urlMapDO = StringUtils.isNotEmpty(hv) ? new Gson().fromJson(hv, UrlMapDO.class) : loadUrlMapFromDb(compressionCode);
        return Objects.isNull(urlMapDO) ? null : urlMapDO;
    }


    private UrlMapDO loadUrlMapFromDb(String compressionCode) {
        // 根据压缩码查找数据库
        UrlMapDO urlMapDO = urlMapGateway.getUrlMapDOByCompressionCode(compressionCode);
        if (Objects.isNull(urlMapDO)) {
            return null;
        }

        // 刷新缓存
        this.refreshUrlMapCache(urlMapDO);
        return urlMapDO;
    }

    /**
     * TODO 缓存预热:在系统启动时，将部分热门压缩码提前加载到缓存中，避免第一次查询时穿透缓存
     */
    @PostConstruct
    public void preloadCache() {

    }
}
