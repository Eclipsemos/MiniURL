# EZLink: High Performance short URL & Analytics 
![](./Architecture.png)


# Tech Stack Terminology
1. Domain Driven Design
2. Reactor 模型：Spring WebFlux
3. 雪花算法
4. 分布式锁
5. 责任链模式
6. TransmittableThreadLocal
7. 消息队列：Kafka
8. 定时任务：Spring Scheduled
9. 缓存：Redis
10. 布隆过滤器：BloomFilter
11. Dubbo (RPC)


# Launch the MiniURL
- Port reset
```
net stop winnat
net start winnat
```
- Dependecies installation
```
docker-compose up -d
```
- Run the Application
```
 mvn springboot:run
```

## 业务流程 1：长链传入，短链创建
### 长链传入 (适配器层)
- 长链接以Json封装形势发送到 ```localhost:8080/api/create```
- UrlMapController的方法createUrlMap收到请求并且把请求封装成ServerWebExchange和UrlMapAddCmd两部分
- createUrlMap方法
- 返回UrlMapDTO格式，通过urlMapService.createUrlMap(urlMapAddCmd).
```
相关注入： 
UrlMapController.java
@Autowired
private UrlMapService urlMapService; //注入应用层的Service
```
### 创建短链流程编排 (应用层)
- urlMapService在应用层实现，这里仅实现了业务的编排流程，并没有实现业务功能。
- urlMapService返回 urlMapAddCmdExe.execute(urlMapAddCmd)
- urlMapCmdExe是执行器，执行器首先运行urlMapDTOAssembler（装配器），把UrlMapAddCmd转换成UrlMapDO格式（UrlMapDO是其在Domain内的格式）。UrlMapAddCmd只有**长链、描述**两个属性，转换后的UrlMapDO具有**短链、长链 、压缩码、描述**四个属性
- 调用领域层的UrlMapDomainService的方法createUrlMap(传入urlMapDO)。
- 返回领域层执行完毕后，进行转换成适合返回给前端的UrlMapDTO格式，这里的UrlMapDTO包括了**长链、短链**两种属性.
```
相关注入：
UrlMapServiceImpl.java
@Autowired
private UrlMapAddCmdExe urlMapAddCmdExe; //注入应用层的执行器


UrlMapAddCmdExe.java
@Autowired
private UrlMapDomainService urlMapDomainService; ///注入领域层的服务（真正的业务逻辑实现）

@Autowired
private UrlMapDTOAssembler urlMapDTOAssembler; //注入应用层的装配器
```
### 进行短链创建的实现（领域层）
- 应用层传入已经处理好格式的urlMapDO到方法createUrlMap
- 获取RLock，RLock是Redisson 中的一个可重入锁（同一个线程多次获取锁而不会死锁），并且配置有自动释放（时间设置）。
- 通过LockKeyEnum枚举类获得CREATE_URL_MAP的相关code，并且以这个code:"ezlink:url:map:create"作为参数传入distributedLockFactory.getLock中获得一个锁对象。在这释放之前，如果其他线程想要以同样的code获得RLock对象时将会等待，直到锁被释放。
- 验证传入的urlMapDO中的长链接是否合法，使用阿帕奇包中的UrlValidator实现。
- 调用领域层方法getAvailableCompressionCodeDO，传入刚刚获得的长链接来获得一个CompressionCodeDO对象。这里的CompressionCodeDO有**id，压缩码，状态，序列值，生成策略** 。
- 领域层方法getAvailableCompressionCodeDO首先尝试调用基础设施层的CompressionCodeGatewayImpl网关中的getLatestAvailableCompressionCodeDO方法
- 这个方法是用MyBatis的XML注入实现（CompressionCodeMapper），其在compression_code表中找到状态码为1（未被使用，被使用是2）且未被删除的已生成短链信息，返回格式为CompressionCodeDO。
- 判断getLatestAvailableCompressionCodeDO返回的CompressionCodeDO是否为空（没有可用的压缩码）。如果非空，则返回给领域层的createUrlMap方法。 如果为空，则执行generateBatchCompressionCode(longUrl)先生成压缩码。***这里是因为如果并不是每创建一次短链，就生成一个压缩码，而是没有压缩码的时候一次性生成多个压缩码，后续的短链创建请求直接从之前生成的压缩码中取一个还没有使用的就可以了。***
- generateBatchCompressionCode默认生成10个压缩码。其首先创建一个CompressionCodeDO类，设置其生成策略。然后传入长链调用sequenceGenerator的方法generate生成SequenceAndCodeDO类（包含了**序列号、压缩码**）。
- sequenceGenerator是领域层的核心业务类，其中的generate方法首先调用方法generateSequence（可选哈希、ID生成器两种），使用murmurHash算出HashCode获得10进制的数，以long的形式返回。然后再将其转换为62进制（为什么是62进制？因为0-9一共10个数字，加上大小写字母26*2一共62个字符）
- 在生成完62进制压缩码后，使用布隆过滤器(fpp=0.00001,误判率越低，布隆过滤器内部使用的位数组就越大)检查是否刚生产的压缩吗可能在，如果可能在，就不断生成直到不在为止。接着把刚生产的放入到布隆过滤器中。
- 返回SequenceAndCodeDO结果（包含了10进制的Squence和62进制的CompressionCode）
- generateBatchCompressionCode收到生成的SequenceAndCodeDO结果后设置compressionCodeDO，并且使用网关compressionCodeGateway（基础设施层）插入生成的compressionCodeDO到数据库表compression_code中
- 回到领域层的createURL中，首先检查获得的compressionCodeDO是否合法，然后再生成短链（即protocol://domain//compressionCode)
- 调用saveUrlMapAndUpdateCompressCode方法来写入到基础设施层中的数据表url_map和compression_code.
- 调用urlMapCacheManager的方法refreshUrlMapCache刷新到插入结果到缓存中. 该阶段把对象（key,field,value）存入Redis中。
- 准备解锁，先判断当前线程是否有锁。
- 完成短链创建。

## 业务流程 2：长链重定向
### 获得短链访问请求（适配器层）
- compressionCode作为RESTful的请求直接被DispatchController接收到，并且将其分为两部分：ServerWebExchange以及String类型的compressionCode。
- 把刚刚获得的两部分封装到dispatchQry中，并且传入并且调用应用层的dispatchService的dispatch方法
- 和业务1一样，应用层的dispatchService是在应用层内的DispatchServiceImpl实现，并且调用了执行器dispatchQryExe来执行dispatchQry。
### 执行器（应用层）
- DispatchQryExe执行器中注入了领域层UrlMapDomainService和领域层WebFluxServerResponseWriter
- 其中execute方法的类型是```Mono<Void>```，代该操作异步执行。
- 首先调用generateTransformContext填充一个TransformContext的上下文（把exchange内的request内的headers提取，使用```Set<String>```获取请求头Key的合集，并且一个个赋值到context中的header中去）
### 处理上下文（领域层）
- 接下来执行器会调用领域层的UrlMapDomainService的方法processTransform处理刚刚获得的上下文context。
- processTransform首先构造了过滤器链（责任链模式），使用 Spring Framework 的功能来动态查找和获取所有实现了 TransformFilter 接口的 bean 实例，将其存入TransformFilterInstance的List数组。
- 对刚刚获得的```List<TransformFilterInstance>```进行排序（按照每个实现类的getOrder从大到小），使用BaseNamingTransformFilter(没有@Component标注)封装过滤器，初始化过程包括重写其filterName方法。
- 最后将处理好的过滤器添加到封装好的过滤器中，执行init（context）方法初始化改过滤器。
- 接着使用过滤器链处理上下文。在过滤器链中，按顺序执行
- 第一个过滤器：ExtractRequestHeaderTransformFilter：提取请求头中的一些参数如 User-Agent、Cookie、ClientId、IP 存到 TransformContext 的 ```Map<String, Object> params``` 字段中
- 第二个过滤器：UrlTransformFilter：短链转换，根据前端传入的 compressionCode 找到映射记录，如果能够成功找到，那么将 TransformContext 的 transformStatus 字段设为 TRANSFORM_SUCCESS，同时将短链和长链也存到 TransformContext 的 ```Map<String, Object> params``` 字段中。这其中是直接去缓存中查找映射记录（因为之前长链和短链已经写入了redis缓存中。该阶段首先查看布隆过滤器防止缓存穿透，接着使用查询redis中的哈希表获得value（UrlMapDo类型）。如果redis中查不到，就去使用网关查询基础设施层中的DB，查不到返回null，查到返回。
- 第三个过滤器：RedirectionTransformFilter：赋值 redirection 字段，同时修改 TransformContext 的 transformStatus 字段为 REDIRECTION_SUCCESS（重定向成功）
- 第四个过滤器:TransformEventProcessTransformFilter：如果 TransformContext 的 transformStatus 字段为 REDIRECTION_SUCCESS，说明重定向成功，那么我们需要记录下这次的重定向日志到数据库表 transform_event_record 中，记录短链、长链、重定向时间、用户 IP 等。这里创建好插入的消息后，并没有实时的进行，而是发送了一条Kafka的消息，其消费者在适配器层中的TransformEventConsumer。
### 异步操作执行（应用层）
- 过滤器链执行完毕，回到应用层，执行构造好的TTL(publishOn(Schedulers.parallel()) 这一行的作用是确保 Mono.fromRunnable(context.getRedirectAction()) 后续的操作（如 doOnSuccess）在一个并行的线程上执行，而不是在当前线程或调用者的线程上)。

## 业务流程 3: 接口限流
