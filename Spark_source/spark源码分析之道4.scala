5,计算引擎

Spark的计算式一个层层迭代的过程
RDD式对各种数据计算模型的统一抽象,被用来迭代计算过程和任务输出结果的缓存读写.
sdhuffle式连接map和reduce的桥梁.
shuffle的性能优劣,直接决定了计算引擎的性能和吞吐.

5.1 迭代计算
RDD.scala的iterator方法是迭代计算的根源
final def iterator(split: Partition, context: TaskContext): Iterator[T] = {
  if (storageLevel != StorageLevel.NONE) {
    getOrCompute(split, context)
  } else {
    computeOrReadCheckpoint(split, context)
  }
}
如果storage等级非none,说明有缓存,就调用 getOrCompute;传入split和context
反之,computeOrReadCheckpoint;要么计算,要么读取checkpoint里面的数据
getOrCompute在同一个RDD.scala文件中

computeOrReadCheckpoint的实现也是在RDD.scala中
private[spark] def computeOrReadCheckpoint(split: Partition, context: TaskContext): Iterator[T] =
{
  if (isCheckpointedAndMaterialized) {
    firstParent[T].iterator(split, context)
  } else {
    compute(split, context)
  }
}
如果是ckpt,而且已经materialize了,就利用iterator;找到其父RDD,调用iterator方法,其实就是上面的iterator方法

如果不是,计算context
这里原书说,查看线程栈更加直观,这个查看线程栈的方式我还没有找到...
跳过;在HadoopRDD中,看compute方法
首先,利用
	val iter = new NextIterator[(K, V)]
初始化 NextIterator;
1,从broadcast中获取jobconf;
2,创建InputMetrics,用来计算字节读取的测量信息;在recordreader正式读取数据之前创建bytereadcallback,用来获取当前线程从文件系统读取的字节数
3,获取input格式;
4,使用addlocalconfiguration,给jobconf添加hadoop任务相关配置
5,创建RecordReader
下面是定义的几个方法;先掠过
6,将 NextIterator封装为 InterruptibleIterator

rdd.terator调用结束后,会调用 SortShuffleWriter.scala的 write方法
1,创建 ExternalSorter;将计算结果写入缓存
2,调用 val output = shuffleBlockResolver.getDataFile(dep.shuffleId, mapId)
3,创建blockid val blockId = ShuffleBlockId(dep.shuffleId, mapId, IndexShuffleBlockResolver.NOOP_REDUCE_ID)
4,val partitionLengths = sorter.writePartitionedFile(blockId, tmp),将分区结果写入tmp中
5,shuffleBlockResolver.writeIndexFileAndCommit(dep.shuffleId, mapId, partitionLengths, tmp),创建索引
6,mapStatus = MapStatus(blockManager.shuffleServerId, partitionLengths),创建mapstatus


5.2 Shuffle
shuffle,是所有MR计算框架必经之路;shuffle用于打通map任务输出与reduce任务输入;map的输出结果按照key,hash之后,分配给一个reduce任务
早期的shuffle存在的问题:
1,map任务的中间结果首先存入内存然后写入磁盘;对内存开销要求很大.
2,每个map任务产生R个bucket(result任务数量);shuffle需要的bucket=M*R,在shuffle频繁的情况下,磁盘IO将成为性能瓶颈.

reduce任务获取map任务的中间输出时,需要在磁盘上merge sort;这产生了更多的磁盘IO
数据量很小时,map和reduce任务很多时,产生很多网络IO

目前Spark的优化策略:
1,map任务给每个partition的reduce任务输出的bucket合并到一个文件,避免磁盘IO消耗太大
2,map任务逐条输出结果,而不是一次性输出到内存中
3,磁盘+内存共同写,避免内存溢出
4,reduce任务,对于拉取到map任务中间结果逐条读取,而不是一次性读入内存,并在内存中聚合排序;避免占用大量数据
5,reduce任务将要拉取的block按照blockmanager地址划分,将同一个manager的block积累在一起成为少量网络请求,减少网络IO


5.3 map段计算结果缓存处理
两个概念:
bypassMergeThreshold:传递到reduce再做合并操作的阈值;如果partition数量小于该值,不用执行聚合和排序,直接将分区写到executor的存储文件中,最后在reduce端再做串联
bypassMergeSort:是否传递到reduce端再做合并排序;是否直接将各个partition直接写到executor的存储文件中.避免占用大量内存,内存溢出

map端计算结果缓存,3种方式:
map端对计算结果在缓存中聚合排序
map不适用缓存,不执行聚合排序,直接spilltopartitionfiles,将分区写到自己的存储文件,最后由reduce端对计算结果执行合并和排序
map端对计算结果简单缓存

在spark.util.collection.ExternalSorter.scala中,定义的 insertAll
根据我们是否需要combine来处理;一种,是内存优先存储,一种是磁盘优先存储
def insertAll(records: Iterator[Product2[K, V]]): Unit = {
  // TODO: stop combining if we find that the reduction factor isn't high
  val shouldCombine = aggregator.isDefined
  if (shouldCombine) {
    // Combine values in-memory first using our AppendOnlyMap
    val mergeValue = aggregator.get.mergeValue
    val createCombiner = aggregator.get.createCombiner
    var kv: Product2[K, V] = null
    val update = (hadValue: Boolean, oldValue: C) => {
      if (hadValue) mergeValue(oldValue, kv._2) else createCombiner(kv._2)
    }
    while (records.hasNext) {
      addElementsRead()
      kv = records.next()
      map.changeValue((getPartition(kv._1), kv._1), update)
      maybeSpillCollection(usingMap = true)
    }
  } else {
    // Stick values into our buffer
    while (records.hasNext) {
      addElementsRead()
      val kv = records.next()
      buffer.insert(getPartition(kv._1), kv._1, kv._2.asInstanceOf[C])
      maybeSpillCollection(usingMap = false)
    }
  }
}


5.3.1 map端对计算结果在缓存聚合
任务分区很多时,如果将数据存到executor,在reduce中会存在大量网络IO,形成性能瓶颈.reduce读取map的结果变慢,导致其他想要分配到被这些map任务占用的节点的任务需要等待或者分配到更远的节点上;效率低下
在map端,就聚合排序,可以节省IO操作,提升系统性能;
因此需要定义聚合器aggregator函数,用来对结果聚合排序

Externalsorter的insertAll方法实现如下:spark.util.collection.Externalsorter.scala
def insertAll(records: Iterator[Product2[K, V]]): Unit = {
  // TODO: stop combining if we find that the reduction factor isn't high
  val shouldCombine = aggregator.isDefined
  if (shouldCombine) {
    // Combine values in-memory first using our AppendOnlyMap
    val mergeValue = aggregator.get.mergeValue
    val createCombiner = aggregator.get.createCombiner
    var kv: Product2[K, V] = null
    val update = (hadValue: Boolean, oldValue: C) => {
      if (hadValue) mergeValue(oldValue, kv._2) else createCombiner(kv._2)
    }
    while (records.hasNext) {
      addElementsRead()
      kv = records.next()
      map.changeValue((getPartition(kv._1), kv._1), update)
      maybeSpillCollection(usingMap = true)
    }
  } else {
    // Stick values into our buffer
    while (records.hasNext) {
      addElementsRead()
      val kv = records.next()
      buffer.insert(getPartition(kv._1), kv._1, kv._2.asInstanceOf[C])
      maybeSpillCollection(usingMap = false)
    }
  }
}
如果,aggregator是被定义了,那么,设置了聚合函数aggregator;从聚合函数获取 mergeValue,createCombiner 等函数
定义update函数,用于操作 mergeValue,createCombiner 
迭代之前创建的iterator,每读取一条 Product2[K, V],就将每行组服从按照空格切分
调用changeValue
调用maybeSpillCollection,处理sizetrackingappendonlymap溢出

spark.util.collection.SizeTrackingAppendOnlyMap.scala,changeValue方法
override def changeValue(key: K, updateFunc: (Boolean, V) => V): V = {
  val newValue = super.changeValue(key, updateFunc)
  super.afterUpdate()
  newValue
}
调用父类 AppendOnlyMap 的changevalue函数
调用集成的特质SizeTracker的afterUpdate函数

追踪到AppendOnlyMap.scala的AppendOnlyMap类的 changeValue 方法
def changeValue(key: K, updateFunc: (Boolean, V) => V): V = {
  assert(!destroyed, destructionMessage)
  val k = key.asInstanceOf[AnyRef]
  if (k.eq(null)) {
    if (!haveNullValue) {
      incrementSize()
    }
    nullValue = updateFunc(haveNullValue, nullValue)
    haveNullValue = true
    return nullValue
  }
  var pos = rehash(k.hashCode) & mask
  var i = 1
  while (true) {
    val curKey = data(2 * pos)
    if (curKey.eq(null)) {
      val newValue = updateFunc(false, null.asInstanceOf[V])
      data(2 * pos) = k
      data(2 * pos + 1) = newValue.asInstanceOf[AnyRef]
      incrementSize()
      return newValue
    } else if (k.eq(curKey) || k.equals(curKey)) {
      val newValue = updateFunc(true, data(2 * pos + 1).asInstanceOf[V])
      data(2 * pos + 1) = newValue.asInstanceOf[AnyRef]
      return newValue
    } else {
      val delta = i
      pos = (pos + delta) & mask
      i += 1
    }
  }
  null.asInstanceOf[V] // Never reached but needed to keep compiler happy
}

incrementSize 方法用于扩充AppendOnlyMap的容量
private def incrementSize() {
  curSize += 1
  if (curSize > growThreshold) {
    growTable()
  }
}

下钻,growTable 方法;用来加倍表格容量,重新hash

AppendOnlyMap 的大小的采样
为了控制spark数据量的大小,通过采样,估算或者推测 AppendOnlyMap 未来的大小,从而控制.
SizeTrackingAppendOnlyMap 继承了 SizeTracker;afterupdate方法用于每次更新 AppendOnlyMap的缓存后进行采样;采样前提是达到设定的采样间隔

采样的步骤
private def takeSample(): Unit = {
  samples.enqueue(Sample(SizeEstimator.estimate(this), numUpdates))
  // Only use the last two samples to extrapolate
  if (samples.size > 2) {
    samples.dequeue()
  }
  val bytesDelta = samples.toList.reverse match {
    case latest :: previous :: tail =>
      (latest.size - previous.size).toDouble / (latest.numUpdates - previous.numUpdates)
    // If fewer than 2 samples, assume no change
    case _ => 0
  }
  bytesPerUpdate = math.max(0, bytesDelta)
  nextSampleNum = math.ceil(numUpdates * SAMPLE_GROWTH_RATE).toLong
}

将内存进行估算,于当前编号一起作为样本数据更新到samples中
如果当前采样数量>2,执行dequeue,只留2个采样
计算更新增加的大小;如果样本数<2,为0
计算下次采样间隔nextSampleNum

AppendOnlyMap的大小采样数据用于推测 AppendOnlyMap未来的大小;实际上是SizeTracker.scala的 estimateSize
def estimateSize(): Long = {
  assert(samples.nonEmpty)
  val extrapolatedDelta = bytesPerUpdate * (numUpdates - samples.last.numUpdates)
  (samples.last.size + extrapolatedDelta).toLong
}
限制内存中的容量,以防止内存溢出

5.3.2 map端计算结果缓存
ExternalSorter.scala的ExternalSorter类,insertAll方法中,if没有定义aggregator,也就是没定义聚合方法,那么就不用combine
{
  // Stick values into our buffer
  while (records.hasNext) {
    addElementsRead()
    val kv = records.next()
    buffer.insert(getPartition(kv._1), kv._1, kv._2.asInstanceOf[C])
    maybeSpillCollection(usingMap = false)
  }
}

PartitionedPairBuffer.insert方法,把计算结果缓存到数组中;
穿透后发现,是通过growArray,新建2倍大小的新数组,然后简单复制而已.

ExternalSorter.scala中定义了 maybeSpillCollection 方法,用来处理 SizeTrackingPairBuffer溢出;

5.3.3 容量限制
AppendOnlyMap 和 SizeTrackingPairBuffer的容量都可以增长;数据量不大时可以解决;但是数据量爆炸时,会撑爆内存.
是否溢出?通过maybeSpill来判断;见spark.util.collection.Spillable.scala
protected def maybeSpill(collection: C, currentMemory: Long): Boolean = {
  var shouldSpill = false
  if (elementsRead % 32 == 0 && currentMemory >= myMemoryThreshold) {
    // Claim up to double our current memory from the shuffle memory pool
    val amountToRequest = 2 * currentMemory - myMemoryThreshold
    val granted = acquireMemory(amountToRequest)
    myMemoryThreshold += granted
    // If we were granted too little memory to grow further (either tryToAcquire returned 0,
    // or we already had more memory than myMemoryThreshold), spill the current collection
    shouldSpill = currentMemory >= myMemoryThreshold
  }
  shouldSpill = shouldSpill || _elementsRead > numElementsForceSpillThreshold
  // Actually spill
  if (shouldSpill) {
    _spillCount += 1
    logSpillage(currentMemory)
    spill(collection)
    _elementsRead = 0
    _memoryBytesSpilled += currentMemory
    releaseMemory()
  }
  shouldSpill
}
首先,获取 amountToRequest = 2 * currentMemory - myMemoryThreshold
其次,如果获得的内存依然不足,调用spill执行溢出操作.
溢出后,增加内存,释放当前线程占用的内存

溢出操作
如果溢出,见spill方法;
override protected[this] def spill(collection: WritablePartitionedPairCollection[K, C]): Unit = {
  val inMemoryIterator = collection.destructiveSortedWritablePartitionedIterator(comparator)
  val spillFile = spillMemoryIteratorToDisk(inMemoryIterator)
  spills += spillFile
}
和老版本不同的.
这里的 spillMemoryIteratorToDisk 方法,取代了老版本中,spilltomergeablefile方法;实现方法如下
首先 val (blockId, file) = diskBlockManager.createTempShuffleBlock() 创建临时文件;
定义一些参数,如 objectsWritten,写入多少?spillMetrics,溢出的数量;writer,写入工具;batchSizes,batch大小;elementsPerPartition,每个分区多少元素;
然后调用 collection.destructiveSortedWritablePartitionedIterator,排序
将集合内容写入临时文件:
	集合遍历完,flush
	遍历中,每当写入元素个数>批量序列化尺寸时,执行flush;然后重建writer

5.4 map端计算结果持久化
writePartitionedFile 用于持久化计算结果
ExternalSorter.scala中,定义的writePartitionedFile方法;用于持久化计算结果
两个分支:溢出到分区后合并;只在内存中排序合并
溢出到分区后合并:将内存中缓存的多个partition的计算结果分别写入到多个临时block文件中,然后将这些block文件的额内容全部写入正式的block输出文件中
只在内存中排序合并:缓存的中间计算结果按照partition分组后写入block输出文件

5.4.1 溢出分区文件
createTempShuffleBlock方法略.getDiskWriter方法获取写入方法

持久化方法:为每个临时文件最后,逐个读取并统一写入正式的block文件;
每个partition生成的临时文件最后会逐个读取并统一写入正式的block文件->每个map任务实际上最后只会生成一个磁盘文件;多个bucket合并到一个文件中

5.4.2 排序与分区分组
partitionedIterator,通过对集合按照指定的比较器进行排序,并且按照partition id分组
def partitionedIterator: Iterator[(Int, Iterator[Product2[K, C]])] = {
  val usingMap = aggregator.isDefined
  val collection: WritablePartitionedPairCollection[K, C] = if (usingMap) map else buffer
  if (spills.isEmpty) {
    // Special case: if we have only in-memory data, we don't need to merge streams, and perhaps
    // we don't even need to sort by anything other than partition ID
    // 只在内存中有时间,不用merge,甚至可能不需要通过除了partition ID之外的方式进行排序
    if (!ordering.isDefined) {
      // The user hasn't requested sorted keys, so only sort by partition ID, not key
      // 用户不需要要求排序后的keys,因此只用通过partition ID排序,而不是key
      groupByPartition(destructiveIterator(collection.partitionedDestructiveSortedIterator(None)))
    } else {
      // We do need to sort by both partition ID and key
      // 既要ID,又要key
      groupByPartition(destructiveIterator(
        collection.partitionedDestructiveSortedIterator(Some(keyComparator))))
    }
  } else {
    // Merge spilled and in-memory data
    merge(spills, destructiveIterator(
      collection.partitionedDestructiveSortedIterator(comparator)))
  }
}

排序器:
// 比较器,用来聚合或者排序的;可以是部分排序(相同keys有comparator.compare(k, k) = 0),如果没有全排序的话.
// 一些不等keys也有这个,因此我们需要实现真相等的keys.
// 注意,我们忽略了没有聚合器和排序方法的情况.
private val keyComparator: Comparator[K] = ordering.getOrElse(new Comparator[K] {
  override def compare(a: K, b: K): Int = {
    val h1 = if (a == null) 0 else a.hashCode()
    val h2 = if (b == null) 0 else b.hashCode()
    if (h1 < h2) -1 else if (h1 == h2) 0 else 1 }})


partitionedIterator方法中, groupByPartition(destructiveIterator(collection.partitionedDestructiveSortedIterator(None))) 是核心

destructiveIterator方法的实现
这里是ExternalSorter.scala的destructiveIterator的实现方法
def destructiveIterator(memoryIterator: Iterator[((Int, K), C)]): Iterator[((Int, K), C)] = {
  if (isShuffleSort) {
    memoryIterator
  } else {
    readingIterator = new SpillableIterator(memoryIterator)
    readingIterator
  }
}

如果是shufflesort,就输出 memoryIterator;反之,用SpillableIterator

AppendOnlyMap.scala中destructiveIterator的实现如下
def destructiveSortedIterator(keyComparator: Comparator[K]): Iterator[(K, V)] = {
  destroyed = true
  // Pack KV pairs into the front of the underlying array
  var keyIndex, newIndex = 0
  while (keyIndex < capacity) {
    if (data(2 * keyIndex) != null) {
      data(2 * newIndex) = data(2 * keyIndex)
      data(2 * newIndex + 1) = data(2 * keyIndex + 1)
      newIndex += 1
    }
    keyIndex += 1
  }
  assert(curSize == newIndex + (if (haveNullValue) 1 else 0))
  new Sorter(new KVArraySortDataFormat[K, AnyRef]).sort(data, 0, newIndex, keyComparator)
  new Iterator[(K, V)] {
    var i = 0
    var nullValueReady = haveNullValue
    def hasNext: Boolean = (i < newIndex || nullValueReady)
    def next(): (K, V) = {
      if (nullValueReady) {
        nullValueReady = false
        (null.asInstanceOf[K], nullValue)
      } else {
        val item = (data(2 * i).asInstanceOf[K], data(2 * i + 1).asInstanceOf[V])
        i += 1
        item
      }
    }
  }
}

3,分区分组:groupByPartition
主要用于针对destructiveSortedIterator生成的迭代器,按照partition id分组
private def groupByPartition(data: Iterator[((Int, K), C)])
    : Iterator[(Int, Iterator[Product2[K, C]])] =
{
  val buffered = data.buffered
  (0 until numPartitions).iterator.map(p => (p, new IteratorForPartition(p, buffered)))
  //每一个partition,转换成iterator,然后对每一个,都用IteratorForPartition
}
IteratorForPartition的实现如下
private[this] class IteratorForPartition(partitionId: Int, data: BufferedIterator[((Int, K), C)])
  extends Iterator[Product2[K, C]]
{
  override def hasNext: Boolean = data.hasNext && data.head._1._1 == partitionId
  override def next(): Product2[K, C] = {
    if (!hasNext) {
      throw new NoSuchElementException
    }
    val elem = data.next()
    (elem._1._2, elem._2)
  }
}

5.4.3 分区索引文件
无论哪种缓存处理,在持久化的时候都会写入统一文件;reduce如何从文件中按照分区读取数据呢?
writeindexfile方法,会生成分区索引文件;此文件使用偏移量来区分各个分区的计算结果;偏移量是来自于合并排序过程中记录的各个partition的长度

5.5 reduce端,读取中间计算结果
下游,如何读取上游任务计算结果?
ResultTask的计算,是由RDD的iterator方法驱动,最终计算过程会落实到ShuffledRDD的compute方法;
override def compute(split: Partition, context: TaskContext): Iterator[(K, C)] = {
  val dep = dependencies.head.asInstanceOf[ShuffleDependency[K, V, C]]
  SparkEnv.get.shuffleManager.getReader(dep.shuffleHandle, split.index, split.index + 1, context)
    .read()
    .asInstanceOf[Iterator[(K, C)]]
}
首先,调用SortShuffleManager的getReader方法,创建HashShuffleReader;然后调用read方法,读取中间计算结果
虽然我也不清楚,为什么新版本里面的getReader,穿透后是ShuffleManager.scala中的getReader,但是这里面的getReader是空的
又怎么穿透到SortShuffleManager里面了

override def getReader[K, C](
    handle: ShuffleHandle,
    startPartition: Int,
    endPartition: Int,
    context: TaskContext): ShuffleReader[K, C] = {
  new BlockStoreShuffleReader(
    handle.asInstanceOf[BaseShuffleHandle[K, _, C]], startPartition, endPartition, context)
}

老版本里面是 new HashShuffleReader, 新版本是 BlockStoreShuffleReader;

BlockStoreShuffleReader.scala 脚本中,BlockStoreShuffleReader类, 主要内容就是 read方法的实现
从远端或者本地读取中间计算结果
对 InterruptibleIterator 执行聚合
对 InterruptibleIterator 执行排序;用 ExternalSorter的insertAll

从远端或者本地读取中间计算结果,使用 ShuffleBlockFetcherIterator来实现,而不是老版本的 BlockStoreShuffleFetcher的fetch方法实现
具体实现方法见 spark.storage.ShuffleBlockFetcherIterator.scala 文件中

5.5.1 获取map任务状态
spark通过调用 getStatuses,而不是老版本的 getServerStatuses方法
处理步骤:
1,从当前BlockManager的MapOutPutTracker中获取MapStatus.如果没有,进入第二步;如果有,进入第四步
2,如果获取列表中已经存在要取的shuffleId,那么就等待其他线程获取.如果获取列表中不存在要取的shuffleId,那么就将shuffleId放入获取列表
3,调用askTracker,向MapOutputTrackerMasterActor发送 GetMapOutPutStatuses消息,获取map任务的状态信息. MapOutputTrackerMasterActor接收到消息后,将请求的map任务状态序列化后发送给请求方;然后反序列化
4,没有status,就直接返回statuses

protected def askTracker[T: ClassTag](message: Any): T = {
  try {
    trackerEndpoint.askSync[T](message)
  } catch {
    case e: Exception =>
      logError("Error communicating with MapOutputTracker", e)
      throw new SparkException("Error communicating with MapOutputTracker", e)
  }
}
askTracker 的实现,如上.追踪
def askSync[T: ClassTag](message: Any, timeout: RpcTimeout): T = {
  val future = ask[T](message, timeout)
  timeout.awaitResult(future)
}

处理status消息
override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
  case GetMapOutputStatuses(shuffleId: Int) =>
    val hostPort = context.senderAddress.hostPort
    logInfo("Asked to send map output locations for shuffle " + shuffleId + " to " + hostPort)
    val mapOutputStatuses = tracker.post(new GetMapOutputMessage(shuffleId, context))
  case StopMapOutputTracker =>
    logInfo("MapOutputTrackerMasterEndpoint stopped!")
    context.reply(true)
    stop()
}

转换 mapstatuses的实现如下:map 任务地址转换
def convertMapStatuses(
    shuffleId: Int,
    startPartition: Int,
    endPartition: Int,
    statuses: Array[MapStatus]): Iterator[(BlockManagerId, Seq[(BlockId, Long)])] = {
  assert (statuses != null)
  val splitsByAddress = new HashMap[BlockManagerId, ListBuffer[(BlockId, Long)]]
  for ((status, mapId) <- statuses.iterator.zipWithIndex) {
    if (status == null) {
      val errorMessage = s"Missing an output location for shuffle $shuffleId"
      logError(errorMessage)
      throw new MetadataFetchFailedException(shuffleId, startPartition, errorMessage)
    } else {
      for (part <- startPartition until endPartition) {
        val size = status.getSizeForBlock(part)
        if (size != 0) {
          splitsByAddress.getOrElseUpdate(status.location, ListBuffer()) +=
              ((ShuffleBlockId(shuffleId, mapId, part), size))
        }
      }
    }
  }
  splitsByAddress.iterator
}

5.5.2 划分本地与远程Block
ShuffleBlockFetcherIterator 是读取中间结果的关键
在 BlockStoreShuffleReader.read方法中,val wrappedStreams = new ShuffleBlockFetcherIterator(...)
构造 val wrappedStreams = new ShuffleBlockFetcherIterator时,会调用 ShuffleBlockFetcherIterator 类, 该类中调用 initialize方法;实现方法如下:
private[this] def initialize(): Unit = {
  // Add a task completion callback (called in both success case and failure case) to cleanup.
  context.addTaskCompletionListener[Unit](_ => cleanup())
  // Split local and remote blocks.
  val remoteRequests = splitLocalRemoteBlocks()
  // Add the remote requests into our queue in a random order
  fetchRequests ++= Utils.randomize(remoteRequests)
  assert ((0 == reqsInFlight) == (0 == bytesInFlight),
    "expected reqsInFlight = 0 but found reqsInFlight = " + reqsInFlight +
    ", expected bytesInFlight = 0 but found bytesInFlight = " + bytesInFlight)
  // Send out initial requests for blocks, up to our maxBytesInFlight
  fetchUpToMaxBytes()
  val numFetches = remoteRequests.size - fetchRequests.size
  logInfo("Started " + numFetches + " remote fetches in" + Utils.getUsedTimeMs(startTime))
  // Get Local Blocks
  fetchLocalBlocks()
  logDebug("Got local blocks in " + Utils.getUsedTimeMs(startTime))
}

首先通过 val remoteRequests = splitLocalRemoteBlocks(), 初始化 拆分本地读取和远程读取Block的请求
然后,将 fetchRequest 排序后存入 fetchRequests中
遍历 fetchRequests, 远程请求Block中间结果
调用 fetchLocalBlocks,获取本地Block

splitLocalRemoteBlocks方法,时划分哪些Block从本地获取,哪些远程获取,时获取中间计算结果的关键
实现方法如下:

private[this] def splitLocalRemoteBlocks(): ArrayBuffer[FetchRequest] = {
  // Make remote requests at most maxBytesInFlight / 5 in length; the reason to keep them
  // smaller than maxBytesInFlight is to allow multiple, parallel fetches from up to 5
  // nodes, rather than blocking on reading output from one node.
  val targetRequestSize = math.max(maxBytesInFlight / 5, 1L)
  logDebug("maxBytesInFlight: " + maxBytesInFlight + ", targetRequestSize: " + targetRequestSize
    + ", maxBlocksInFlightPerAddress: " + maxBlocksInFlightPerAddress)
  // Split local and remote blocks. Remote blocks are further split into FetchRequests of size
  // at most maxBytesInFlight in order to limit the amount of data in flight.
  val remoteRequests = new ArrayBuffer[FetchRequest]
  for ((address, blockInfos) <- blocksByAddress) {
    if (address.executorId == blockManager.blockManagerId.executorId) {
      blockInfos.find(_._2 <= 0) match {
        case Some((blockId, size)) if size < 0 =>
          throw new BlockException(blockId, "Negative block size " + size)
        case Some((blockId, size)) if size == 0 =>
          throw new BlockException(blockId, "Zero-sized blocks should be excluded.")
        case None => // do nothing.
      }
      localBlocks ++= blockInfos.map(_._1)
      numBlocksToFetch += localBlocks.size
    } else {
      val iterator = blockInfos.iterator
      var curRequestSize = 0L
      var curBlocks = new ArrayBuffer[(BlockId, Long)]
      while (iterator.hasNext) {
        val (blockId, size) = iterator.next()
        if (size < 0) {
          throw new BlockException(blockId, "Negative block size " + size)
        } else if (size == 0) {
          throw new BlockException(blockId, "Zero-sized blocks should be excluded.")
        } else {
          curBlocks += ((blockId, size))
          remoteBlocks += blockId
          numBlocksToFetch += 1
          curRequestSize += size
        }
        if (curRequestSize >= targetRequestSize ||
            curBlocks.size >= maxBlocksInFlightPerAddress) {
          // Add this FetchRequest
          remoteRequests += new FetchRequest(address, curBlocks)
          logDebug(s"Creating fetch request of $curRequestSize at $address "
            + s"with ${curBlocks.size} blocks")
          curBlocks = new ArrayBuffer[(BlockId, Long)]
          curRequestSize = 0
        }
      }
      // Add in the final request
      if (curBlocks.nonEmpty) {
        remoteRequests += new FetchRequest(address, curBlocks)
      }
    }
  }
  logInfo(s"Getting $numBlocksToFetch non-empty blocks including ${localBlocks.size}" +
      s" local blocks and ${remoteBlocks.size} remote blocks")
  remoteRequests
}

解释如下定义:
targetRequestSize,每个远程请求的最大尺寸
totalBlocks:统计Block总数
localBlocks:缓存可以在本地获取的Block的blockId
remoteBlocks:缓存需要远程获取的Block的Id
curBlocks:远程获取的累加缓存,用于保证每个远程请求的尺寸不会太大不会太小
curRequestSize:当前累加到curBlocks中的所有Block大小和
remoteRequests,缓存需要远程请求的FetchRequest对象
numBlocksToFetch:一共要获取的Block数量
maxBytesInFlight:每次请求的最大字节数

首先初始化基础定义
遍历blockInfo,如果blockInfo所在的Executor与当前Executor相同,则将BlockId存入localBlock;否则,将blockinfo的blockid和size累加到curblocks,将blockid存入remoteblocks,currequestsize增加size;
每当currequestsize >= targetrequestsize,则信建fetchrequest,放入remoterequests;
遍历结束,curblocks中如果仍然有缓存的,信建fetchrequest放入remoterequests;此次请求不受maxbytesinflight和targetrequestsize的影响

5.5.3 获取远程block
sendrequest方法中,用于远程请求中间结果;sendrequest利用fetchrequest封装的blockid,size,address等,调用shuffleclient的fetchblocks方法获取结果
private[this] def sendRequest(req: FetchRequest) {
  logDebug("Sending request for %d blocks (%s) from %s".format(
    req.blocks.size, Utils.bytesToString(req.size), req.address.hostPort))
  bytesInFlight += req.size
  reqsInFlight += 1
  // so we can look up the size of each blockID
  val sizeMap = req.blocks.map { case (blockId, size) => (blockId.toString, size) }.toMap
  val remainingBlocks = new HashSet[String]() ++= sizeMap.keys
  val blockIds = req.blocks.map(_._1.toString)
  val address = req.address
  val blockFetchingListener = new BlockFetchingListener {
    override def onBlockFetchSuccess(blockId: String, buf: ManagedBuffer): Unit = {
      // Only add the buffer to results queue if the iterator is not zombie,
      // i.e. cleanup() has not been called yet.
      ShuffleBlockFetcherIterator.this.synchronized {
        if (!isZombie) {
          // Increment the ref count because we need to pass this to a different thread.
          // This needs to be released after use.
          buf.retain()
          remainingBlocks -= blockId
          results.put(new SuccessFetchResult(BlockId(blockId), address, sizeMap(blockId), buf,
            remainingBlocks.isEmpty))
          logDebug("remainingBlocks: " + remainingBlocks)
        }
      }
      logTrace("Got remote block " + blockId + " after " + Utils.getUsedTimeMs(startTime))
    }
    override def onBlockFetchFailure(blockId: String, e: Throwable): Unit = {
      logError(s"Failed to get block(s) from ${req.address.host}:${req.address.port}", e)
      results.put(new FailureFetchResult(BlockId(blockId), address, e))
    }
  }
  // Fetch remote shuffle blocks to disk when the request is too large. Since the shuffle data is
  // already encrypted and compressed over the wire(w.r.t. the related configs), we can just fetch
  // the data and write it to file directly.
  if (req.size > maxReqSizeShuffleToMem) {
    shuffleClient.fetchBlocks(address.host, address.port, address.executorId, blockIds.toArray,
      blockFetchingListener, this)
  } else {
    shuffleClient.fetchBlocks(address.host, address.port, address.executorId, blockIds.toArray,
      blockFetchingListener, null)
  }
}

首先,初始化sizemap,remainingblocks,blockids,aadress等参数,然后,初始化 blockFetchingListener 监听器
然后,利用 shuffleClient.fetchBlocks 来获取其他节点上的中间计算结果

5.5.4 获取本地Block
fetchLocalBlocks,用于对本地中间计算结果的获取;fetchLocalBlocks方法的实现:
private[this] def fetchLocalBlocks() {
  logDebug(s"Start fetching local blocks: ${localBlocks.mkString(", ")}")
  val iter = localBlocks.iterator
  while (iter.hasNext) {
    val blockId = iter.next()
    try {
      val buf = blockManager.getBlockData(blockId)
      shuffleMetrics.incLocalBlocksFetched(1)
      shuffleMetrics.incLocalBytesRead(buf.size)
      buf.retain()
      results.put(new SuccessFetchResult(blockId, blockManager.blockManagerId,
        buf.size(), buf, false))
    } catch {
      case e: Exception =>
        // If we see an exception, stop immediately.
        logError(s"Error occurred while fetching local blocks", e)
        results.put(new FailureFetchResult(blockId, blockManager.blockManagerId, e))
        return
    }
  }
}
初始化迭代器;初始化blockid;
尝试利用blockManager.getBlockData获取数据
然后将中间结果存入results中

5.6 reduce端计算
5.6.1 如何同时处理多个map任务的中间结果
reduce上游,map任务可能有多个;根据分析,中间结果的block和缓存在 ShuffleBlockFetcherIterator的results中去读数据

每次迭代,会先从results中取出一个fetchresult,然后构建迭代器
  override def hasNext: Boolean = numBlocksProcessed < numBlocksToFetch

5.6.2 reduce端,在缓存中,对中间计算结果执行聚合和排序
read方法中,有 combineCombinersByKey方法;reduce端获取map任务计算中间结果时,将 ShuffleBlockFetcherIterator封装
然后通过 dep.aggregator.get.combineCombinersByKey(combinedKeyValuesIterator, context)
def combineCombinersByKey(
    iter: Iterator[_ <: Product2[K, C]],
    context: TaskContext): Iterator[(K, C)] = {
  val combiners = new ExternalAppendOnlyMap[K, C, C](identity, mergeCombiners, mergeCombiners)
  combiners.insertAll(iter)
  updateMetrics(context, combiners)
  combiners.iterator
}

使用 ExternalAppendOnlyMap, 完成聚合
然后调用 ExternalAppendOnlyMap.insertAll;ExternalAppendOnlyMap.iterator等
其实质,也是使用 SizeTrackingAppendOnlyMap.
def insertAll(entries: Iterator[Product2[K, V]]): Unit = {
  if (currentMap == null) {
    throw new IllegalStateException(
      "Cannot insert new elements into a map after calling iterator")
  }
  // An update function for the map that we reuse across entries to avoid allocating
  // a new closure each time
  var curEntry: Product2[K, V] = null
  val update: (Boolean, C) => C = (hadVal, oldVal) => {
    if (hadVal) mergeValue(oldVal, curEntry._2) else createCombiner(curEntry._2)
  }
  while (entries.hasNext) {
    curEntry = entries.next()
    val estimatedSize = currentMap.estimateSize()
    if (estimatedSize > _peakMemoryUsedBytes) {
      _peakMemoryUsedBytes = estimatedSize
    }
    if (maybeSpill(currentMap, estimatedSize)) {
      currentMap = new SizeTrackingAppendOnlyMap[K, C]
    }
    currentMap.changeValue(curEntry._1, update)
    addElementsRead()
  }
}

5.7 map端和reduce端组合分析
5.7.1 在map端溢出分区文件;在reduce端合并组合
bypassMergeSort,标记是否传递到reduce端,再合并排序->不用缓存,而是将数据按照partition写入不同文件,最后按照partition顺序合并写入同一文件;当没有指定聚合,排序函数,而且partition数量较小时
将多个bucket合并到同一个文件,减少map输出文件数量,节省磁盘IO,提升系统性能
5.7.2 map端,简单缓存,排序分组,reduce端合并组合
缓存中,利用指定的排序函数,对数据按照partition或者key排序,最后按照partition顺序合并写入同一文件;没有指定聚合函数而且partition数量大时
多个bucket合并到一个文件中,通过减少map输出的文件数量,节省磁盘IO,提升系统性能
5.7.3 在map端聚合排序分组,在reduce端组合
缓存中,按照key聚合,利用指定的排序函数,对数据按照partition或者key排序,最后按照partition顺序,合并写入同一个文件.当指定了聚合函数时,一般采用这种方式
多个bucket合并到一个文件中,减少map输出文件数量,节省磁盘IO,提升系统性能;对中间输出数据不是一次性读取,而是逐条放入 appendonlymap的缓存,并且对数据进行聚合,减少了中间结果占用的内存大小
对 appendonlymap 的缓存进行溢出判断,超过内存大小时,写入磁盘


5.8 小结
RDD迭代计算,如何容错?缓存和检查点
map任务时如何聚合的?使用 appendonlymap 提供的数据结构来实现聚合
为什么有什么要在map端聚合?为了降低网络IO,提升系统性能
map任务如何输出?按照分区排序或者分组后,生成分区文件
reduce任务,如何获取map端的任务的输出的?通过mapoutputtracker获取map任务所在executor的blockmanagerid和block的大小,然后通过shuffleclient下载
发送请求时,如何优化性能?对请求分批发送,限制分批请求大小,并行发送,将多个请求数据小的请求合并等
