## Business Process 1: Long URL Input and Short URL Creation
### Long URL Input (Adapter Layer)
- The long URL is sent to `localhost:8080/api/create` in JSON format.
- The `createUrlMap` method in `UrlMapController` receives the request and wraps it into two parts: `ServerWebExchange` and `UrlMapAddCmd`.
- `createUrlMap` method returns a `UrlMapDTO` format by calling `urlMapService.createUrlMap(urlMapAddCmd)`.
```
Injection details: 
UrlMapController.java
@Autowired
private UrlMapService urlMapService; 
```
### Short URL Creation Process Orchestration (Application Layer)
- urlMapService is implemented in the application layer, only handling business orchestration without implementing the actual business logic.
- urlMapService returns urlMapAddCmdExe.execute(urlMapAddCmd).
- urlMapCmdExe is an executor that first runs urlMapDTOAssembler (assembler) to convert UrlMapAddCmd to UrlMapDO format. UrlMapAddCmd only has two attributes: long URL and description, while the converted UrlMapDO includes four attributes: short URL, long URL, compression code, and description.
- It calls the domain layer's UrlMapDomainService method createUrlMap (passing in urlMapDO).
- After the domain layer is executed, it is converted to the UrlMapDTO format suitable for front-end return, which includes both long URL and short URL.
```
Injection details:
UrlMapServiceImpl.java
@Autowired
private UrlMapAddCmdExe urlMapAddCmdExe; 


UrlMapAddCmdExe.java
@Autowired
private UrlMapDomainService urlMapDomainService;

@Autowired
private UrlMapDTOAssembler urlMapDTOAssembler; 
```
### Implementation of Short URL Creation (Domain Layer)
- The application layer passes the already processed urlMapDO to the createUrlMap method.
- Obtains an RLock from Redisson (a reentrant lock that avoids deadlock if the same thread acquires the lock multiple times), with auto-release configured.
- Uses LockKeyEnum to get the relevant code for CREATE_URL_MAP, and passes the code ezlink:url:map:create to distributedLockFactory.getLock to get a lock object. Other threads trying to acquire the same code will wait until the lock is released.
- Verifies the legality of the long URL in urlMapDO using Apache's UrlValidator.
- Calls the domain layer method getAvailableCompressionCodeDO to obtain a CompressionCodeDO object (with attributes: ID, compression code, status, sequence, generation strategy).
- The getAvailableCompressionCodeDO method first calls the infrastructure layer gateway CompressionCodeGatewayImpl's getLatestAvailableCompressionCodeDO method.
- This method, implemented via MyBatis XML injection (CompressionCodeMapper), finds unused and undeleted compression codes in the compression_code table and returns them as CompressionCodeDO.
- If CompressionCodeDO is not empty, it returns to the domain layer. If it is empty, the generateBatchCompressionCode(longUrl) method is executed to generate compression codes in bulk (default is 10).
- generateBatchCompressionCode creates a CompressionCodeDO, sets its generation strategy, and calls the sequenceGenerator.generate method to generate a SequenceAndCodeDO (containing sequence number and compression code).
- The sequenceGenerator is a core domain class, and its generate method first calls generateSequence (supports hash or ID generators), calculates a decimal number using murmurHash, and then converts it to base 62 (0-9, a-z, A-Z).
- After generating the base 62 code, a Bloom filter (with fpp=0.00001) checks for potential existence, and codes are continuously generated until unique. The new code is added to the filter.
- The result is returned to generateBatchCompressionCode, where the generated codes are inserted into the compression_code table.
- Back in the domain layer, the legality of CompressionCodeDO is checked, the short URL is generated (protocol://domain//compressionCode), and saveUrlMapAndUpdateCompressCode saves the data to the url_map and compression_code tables.
- The urlMapCacheManager.refreshUrlMapCache method refreshes the cache with the new data (Redis).
- Unlocks and completes short URL creation.。

## Business Process 2: Long URL Redirection

### Executing the Context (Domain Layer)
- The executor calls the domain layer’s `UrlMapDomainService.processTransform` method to handle the context that was just obtained.
- `processTransform` first constructs a filter chain (using the chain of responsibility pattern). It uses Spring Framework to dynamically locate and retrieve all bean instances that implement the `TransformFilter` interface, and stores them in a `List<TransformFilterInstance>`.
- The `List<TransformFilterInstance>` is then sorted (based on the `getOrder` of each implementation class from large to small), and wrapped in a `BaseNamingTransformFilter` (without the `@Component` annotation). This includes overriding the `filterName` method.
- After preparing the filter, it is added to the filter chain and the `init(context)` method is executed to initialize the filter.
- The context is processed using the filter chain, and the filters are executed in order.
  
### Filters in the Chain:
1. **ExtractRequestHeaderTransformFilter**: Extracts parameters from the request headers such as User-Agent, Cookie, ClientId, and IP, and stores them in the `TransformContext` under the field `Map<String, Object> params`.
2. **UrlTransformFilter**: Performs short URL transformation. Based on the `compressionCode` provided by the front-end, it finds the corresponding mapping record. If successful, the `transformStatus` field in `TransformContext` is set to `TRANSFORM_SUCCESS`, and both the short URL and long URL are stored in `TransformContext`'s `params` map. This checks the cache for the mapping (since the long and short URLs were previously written to Redis). First, the Bloom filter is checked to prevent cache penetration, and then the hash table in Redis is queried for the `UrlMapDo` value. If Redis does not have the record, the database in the infrastructure layer is queried via a gateway. If the DB also has no record, `null` is returned; otherwise, the result is returned.
3. **RedirectionTransformFilter**: Assigns the redirection field and sets the `transformStatus` in `TransformContext` to `REDIRECTION_SUCCESS` (indicating a successful redirection). This is the core functionality, implemented through Spring WebFlux.
4. **TransformEventProcessTransformFilter**: If the `transformStatus` in `TransformContext` is `REDIRECTION_SUCCESS`, this indicates a successful redirection, and the redirection log (including short URL, long URL, redirection time, and user IP) is recorded into the `transform_event_record` database table. Although the message is prepared for insertion, it is not immediately written but is sent as a Kafka message. Its consumer is in the adapter layer in `TransformEventConsumer`.

### Asynchronous Execution (Application Layer)
- Once the filter chain has completed, control returns to the application layer. The constructed `TTL(publishOn(Schedulers.parallel())` ensures that the subsequent operations after `Mono.fromRunnable(context.getRedirectAction())` (such as `doOnSuccess`) are executed on a parallel thread rather than the current or caller's thread.

## Business Process 3: Rate Limiting
### Result
- Global rate limiting on the current interface (100 requests per minute).
- Rate limiting per IP address (e.g., 100 requests per minute per IP).

### Implementation: Rate Limiting Annotations and Lua Script
- An enum class is created to handle different types of rate limiting.
- A custom `RedisTemplate` is implemented (Spring Data Redis by default uses `JdkSerializationRedisSerializer`, which can add unwanted prefixes to keys and values in Redis, causing errors when manually querying. So, the serialization scheme in `RedisTemplate` is modified).
- Lua script: Handles atomic operations in Redis. Lua scripts are used for atomic operations because when Redis executes a Lua script, the entire script is treated as a single execution unit. No other Redis commands or scripts are allowed to execute during this time, ensuring atomicity. Since Redis is single-threaded, there are no context switches during script execution.

## Business Process 4: Dubbo RPC
- Service consumers only need to import the client jar package to invoke the methods defined in `UrlMapService` via RPC.

## Business Process 5: Access Statistics
- In DDD architecture, external jobs are usually written in the adapter layer, and the scheduled task framework is directly implemented using Spring Scheduled.
