package solrconnect

import org.apache.kafka.connect.source.{SourceRecord, SourceTask}
import org.apache.kafka.connect.source.SourceRecord
import java.io.IOException
import java.util

import org.apache.solr.client.solrj.impl.CloudSolrClient

import scala.collection.JavaConverters._
import org.slf4j.LoggerFactory

class SolrSourceTask extends SourceTask {

  private val log = LoggerFactory.getLogger(classOf[SolrSourceTask])

  var topicPrefix:String = ""
  var query = "*:*"
  var collectionName:String = _
  var zkHost:String = _
  var zkChroot:String = _
  var batchSize:Int = 10
  var pollDuration = 5000

  var client:CloudSolrClient = _
  var cursorMark = "*"

  def stop(): Unit = {
    log.info("Closing open client connections")
    SolrClient.clients.values.foreach(_.close)
  }

  override def start(props: util.Map[String, String]): Unit = {
    topicPrefix = props.get("topicPrefix")
    zkHost = props.get("zkHost")
    zkChroot = props.get("zkChroot")
    collectionName = props.get("collectionName")
    batchSize = props.get("batchSize").toInt
    query = props.get("query")


    cursorMark = getCurrentCursorMark(collectionName)
    client = SolrClient.getClient(zkHost, zkChroot)
    client.setDefaultCollection(collectionName)
    SchemaManager.initSchema(zkHost, zkChroot, collectionName)
  }

  override def poll(): util.List[SourceRecord] = {
    try {
      val records = new util.ArrayList[SourceRecord]

      val (nextCursorMark, solrDocs) = SolrClient.querySolr(client, query, batchSize, cursorMark)

      if(cursorMark == nextCursorMark) {
        log.info("No update in cursor-mark. Sleeping for " + pollDuration)
        Thread.sleep(pollDuration)
      } else {
        solrDocs.foreach { doc =>
          val msg = SchemaManager.solrDocToKafkaMsg(doc)
          val sourcePartition = getPartition(collectionName)
          val sourceOffset = getOffset(nextCursorMark)
          val topic = topicPrefix + collectionName
          val schema = SchemaManager.SOLR_SCHEMA

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

  override def version(): String = new SolrSourceConnector().version()

  def getCurrentCursorMark(collectionName:String):String = {
    val offset = context.offsetStorageReader().offset(getPartition(collectionName))

    if(offset == null) cursorMark else {
      offset.get("cursorMark").asInstanceOf[String]
    }
  }

  def getPartition(collectionName:String) = {
    Map("collectionName" -> collectionName).asJava
  }

  def getOffset(cursorMark:String) = {
    Map("cursorMark" -> cursorMark).asJava
  }


}