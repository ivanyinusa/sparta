/**
 * Copyright (C) 2016 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparkta.serving.api.utils

import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.util.Try

import akka.actor._
import akka.pattern.ask
import org.apache.curator.framework.CuratorFramework

import com.stratio.sparkta.driver.service.StreamingContextService
import com.stratio.sparkta.serving.api.actor.SparkStreamingContextActor.Start
import com.stratio.sparkta.serving.api.actor.{ClusterLauncherActor, LocalSparkStreamingContextActor}
import com.stratio.sparkta.serving.api.helpers.SparktaHelper._
import com.stratio.sparkta.serving.core.SparktaConfig
import com.stratio.sparkta.serving.core.models.AggregationPoliciesModel
import com.stratio.sparkta.serving.core.policy.status.PolicyStatusEnum

trait SparkStreamingContextUtils extends PolicyStatusUtils
  with PolicyUtils {

  val SparkStreamingContextActorPrefix: String = "sparkStreamingContextActor"

  def launch(policy: AggregationPoliciesModel,
             policyStatusActor: ActorRef,
             streamingContextService: StreamingContextService,
             context: ActorContext): Future[Try[AggregationPoliciesModel]] =
    for {
      isAvailable <- isContextAvailable(policyStatusActor)
    } yield Try {
      updatePolicy(policy, PolicyStatusEnum.Launched, policyStatusActor)
      getStreamingContextActor(policy, policyStatusActor, streamingContextService, context) match {
        case Some(streamingContextActor) => startPolicy(streamingContextActor)
        case None => updatePolicy(policy, PolicyStatusEnum.Failed, policyStatusActor)
      }
      policy
    }

  def startPolicy(streamingContextActor: ActorRef): Future[Any] = {
    streamingContextActor ? Start
  }

  def createNewPolicy(policy: AggregationPoliciesModel,
                      policyStatusActor: ActorRef,
                      curatorFramework: CuratorFramework,
                      streamingContextService: StreamingContextService,
                      context: ActorContext): AggregationPoliciesModel =
    existsByName(policy.name, None, curatorFramework) match {
      case true =>
        log.error(s"${policy.name} already exists. Try deleting first or choosing another name.")
        throw new RuntimeException(s"${policy.name} already exists")
      case false => launchNewPolicy(policy, policyStatusActor, curatorFramework, streamingContextService, context)
    }

  def launchNewPolicy(policy: AggregationPoliciesModel,
                      policyStatusActor: ActorRef,
                      curatorFramework: CuratorFramework,
                      streamingContextService: StreamingContextService,
                      context: ActorContext): AggregationPoliciesModel = {
    val policyWithIdModel = policyWithId(policy)
    for {
      _ <- createPolicy(policyStatusActor, policyWithIdModel)
    } yield {
      savePolicyInZk(policyWithIdModel, curatorFramework)
      launch(policyWithIdModel, policyStatusActor, streamingContextService, context)
    }
    policyWithIdModel
  }

  def getStreamingContextActor(policy: AggregationPoliciesModel,
                               policyStatusActor: ActorRef,
                               streamingContextService: StreamingContextService,
                               context: ActorContext): Option[ActorRef] = {
    val actorName = s"$SparkStreamingContextActorPrefix-${policy.name.replace(" ", "_")}"
    SparktaConfig.getClusterConfig match {
      case Some(clusterConfig) => {
        log.info(s"launched -> $actorName")
        getClusterLauncher(policy, policyStatusActor, context, actorName)
      }
      case None => {
        killPolicy(policyStatusActor, actorName)
        getLocalLauncher(policy, policyStatusActor, streamingContextService, context, actorName)
      }
    }
  }

  def getLocalLauncher(policy: AggregationPoliciesModel,
                       policyStatusActor: ActorRef,
                       streamingContextService: StreamingContextService,
                       context: ActorContext,
                       actorName: String): Option[ActorRef] = {
    Some(context.actorOf(Props(new LocalSparkStreamingContextActor(policy, streamingContextService, policyStatusActor)),
      actorName))
  }

  def getClusterLauncher(policy: AggregationPoliciesModel,
                         policyStatusActor: ActorRef,
                         context: ActorContext,
                         actorName: String): Option[ActorRef] = {
    Some(context.actorOf(Props(new ClusterLauncherActor(policy, policyStatusActor)), actorName))
  }
}
