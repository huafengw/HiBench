/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.hibench.streambench.gearpump.task

import com.intel.hibench.streambench.gearpump.util.GearpumpConfig
import org.apache.gearpump.Message
import org.apache.gearpump.cluster.UserConfig
import org.apache.gearpump.streaming.task.{Task, TaskContext}

//Todo
class SlidingWindow(taskContext: TaskContext, conf: UserConfig) extends Task(taskContext, conf)  {
  private val benchConfig = conf.getValue[GearpumpConfig](GearpumpConfig.BENCH_CONFIG).get
  private val windowDuration = benchConfig.windowDuration
  private val windowStep = benchConfig.windowSlideStep
  import taskContext.output

  // windowStartTime -> (ip -> (minMessageTime, count))
  private val windowCounts = new TreeSortedMap[Long, UnifiedMap[String, (TimeStamp, Long)]]

  override def onNext(message: Message): Unit = {
    val ip = message.msg.asInstanceOf[String]
    val msgTime = message.timestamp
    getWindows(msgTime).foreach { window =>
      val countsByIp = windowCounts.getOrDefault(window, new UnifiedMap[String, (TimeStamp, Long)])
      val (minTime, count) = countsByIp.getOrDefault(ip, (msgTime, 0L))
      countsByIp.put(ip, (Math.min(msgTime, minTime), count + 1L))
      windowCounts.put(window, countsByIp)
    }

    val reporter = new KafkaReporter(benchConfig.reporterTopic, benchConfig.brokerList)
    var windowStart = windowCounts.firstKey()
    while (taskContext.upstreamMinClock >= (windowStart + windowDuration)) {
      val countsByIp = windowCounts.remove(windowStart)
      countsByIp.forEachValue(new Procedure[(TimeStamp, Long)]() {

        override def value(tuple: (TimeStamp, Long)): Unit = {
          assert(tuple._1 >= windowStart && tuple._1 <= (windowStart + windowDuration) )
          (1 to tuple._2.toInt).foreach(i =>reporter.report(tuple._1, System.currentTimeMillis()))
        }
      })
      windowStart = windowCounts.firstKey()
    }
  }


  private def getWindows(timestamp: TimeStamp): List[TimeStamp] = {
    val windows = ArrayBuffer.empty[TimeStamp]
    var start = lastStartFor(timestamp)
    windows += start
    start -= windowStep
    while (start >= timestamp) {
      windows += start
      start -= windowStep
    }
    windows.toList
  }

  private def lastStartFor(timestamp: TimeStamp): TimeStamp = {
    timestamp - (timestamp + windowStep) % windowStep
  }
}
