package solrconnect

import org.apache.kafka.connect.source.SourceTask
import org.apache.kafka.connect.source.SourceRecord
import java.io.IOException
import java.util
import solrconnect.Constants.Props._
import scala.collection.JavaConverters._

class SolrSourceTask extends SourceTask with Logging {

  var topicPrefix: String = ""
  var query = "*:*"
  var collectionName: String = _
  var zkHost: String = _
  var zkChroot: String = _
  var batchSize: Int = 10

  var pollDuration = 5000
  var client: SolrClient = _
  var schemaManager: SchemaManager = _
  var cursorMark = "*"

  override def version(): String = new SolrSourceConnector().version()

  override def start(props: util.Map[String, String]): Unit = {
    topicPrefix = props.get(TOPIC_PREFIX)
    zkHost = props.get(ZK_HOST)
    zkChroot = props.get(ZK_CHROOT)
    collectionName = props.get(COLLECTION_NAME)
    batchSize = props.get(BATCH_SIZE).toInt
    query = props.get(QUERY)

    cursorMark = getCurrentCursorMark(collectionName)
    client = SolrClient(zkHost, zkChroot, collectionName)

    schemaManager = SchemaManager(zkHost, zkChroot, collectionName)
  }

  def stop(): Unit = {
    log.info("Closing open client connection")
    client.close()
  }

  override def poll(): util.List[SourceRecord] = {
    try {
      val records = new util.ArrayList[SourceRecord]

      val (nextCursorMark, solrDocs) = client.querySolr(query, batchSize, cursorMark)

      if (cursorMark == nextCursorMark) {
        log.info("No update in cursor-mark. Sleeping for " + pollDuration)
        Thread.sleep(pollDuration)
      } else {
        solrDocs.foreach { doc =>
          val msg = schemaManager.convertSolrDocToKafkaMsg(doc)
          val sourcePartition = getPartition(collectionName)
          val sourceOffset = getOffset(nextCursorMark)
          val topic = topicPrefix + collectionName
          val schema = schemaManager.SOLR_SCHEMA

          val record = new SourceRecord(
            sourcePartition,
            sourceOffset,
            topic,
            schema,
            msg
          )
          log.info("Adding new record. " + msg)
          records.add(record)
        }
        cursorMark = nextCursorMark
      }

      records
    } catch {
      case e: IOException =>
        e.printStackTrace()
        throw e
    }
  }

  private def getCurrentCursorMark(collectionName: String): String = {
    val offset = context.offsetStorageReader().offset(getPartition(collectionName))

    if (offset == null) cursorMark else {
      offset.get("cursorMark").asInstanceOf[String]
    }
  }

  private def getPartition(collectionName: String): util.Map[String, String] = {
    Map("collectionName" -> collectionName).asJava
  }

  private def getOffset(cursorMark: String): util.Map[String, String] = {
    Map("cursorMark" -> cursorMark).asJava
  }

}
