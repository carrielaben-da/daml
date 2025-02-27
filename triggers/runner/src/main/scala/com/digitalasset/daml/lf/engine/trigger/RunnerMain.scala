// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf.engine.trigger

import java.io.File

import akka.actor.ActorSystem
import akka.stream._
import com.daml.auth.TokenHolder
import com.daml.grpc.adapter.AkkaExecutionSequencerPool
import com.daml.ledger.api.refinements.ApiTypes.ApplicationId
import com.daml.ledger.client.LedgerClient
import com.daml.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement,
}
import com.daml.lf.archive.{Dar, DarDecoder}
import com.daml.lf.data.Ref.{Identifier, PackageId, QualifiedName}
import com.daml.lf.language.Ast._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

object RunnerMain {

  def listTriggers(darPath: File, dar: Dar[(PackageId, Package)]) = {
    println(s"Listing triggers in $darPath:")
    for ((modName, mod) <- dar.main._2.modules) {
      for ((defName, defVal) <- mod.definitions) {
        defVal match {
          case DValue(TApp(TTyCon(tcon), _), _, _, _) => {
            val triggerIds = TriggerIds(tcon.packageId)
            if (
              tcon == triggerIds.damlTrigger("Trigger")
              || tcon == triggerIds.damlTriggerLowLevel("Trigger")
            ) {
              println(s"  $modName:$defName")
            }
          }
          case _ => {}
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {

    RunnerConfig.parse(args) match {
      case None => sys.exit(1)
      case Some(config) => {
        val dar: Dar[(PackageId, Package)] =
          DarDecoder.assertReadArchiveFromFile(config.darPath.toFile)

        if (config.listTriggers) {
          listTriggers(config.darPath.toFile, dar)
          sys.exit(0)
        }

        val triggerId: Identifier =
          Identifier(dar.main._1, QualifiedName.assertFromString(config.triggerIdentifier))

        val system: ActorSystem = ActorSystem("TriggerRunner")
        implicit val materializer: Materializer = Materializer(system)
        val sequencer = new AkkaExecutionSequencerPool("TriggerRunnerPool")(system)
        implicit val ec: ExecutionContext = system.dispatcher

        val tokenHolder = config.accessTokenFile.map(new TokenHolder(_))
        // We probably want to refresh the token at some point but given that triggers
        // are expected to be written such that they can be killed and restarted at
        // any time it would in principle also be fine to just have the auth failure due
        // to an expired token tear the trigger down and have some external monitoring process (e.g. systemd)
        // restart it.
        val clientConfig = LedgerClientConfiguration(
          applicationId = ApplicationId.unwrap(config.applicationId),
          ledgerIdRequirement = LedgerIdRequirement.none,
          commandClient =
            CommandClientConfiguration.default.copy(defaultDeduplicationTime = config.commandTtl),
          sslContext = config.tlsConfig.client(),
          token = tokenHolder.flatMap(_.token),
          maxInboundMessageSize = config.maxInboundMessageSize,
        )

        val flow: Future[Unit] = for {
          client <- LedgerClient.singleHost(
            config.ledgerHost,
            config.ledgerPort,
            clientConfig,
          )(ec, sequencer)

          _ <- Runner.run(
            dar,
            triggerId,
            client,
            config.timeProviderType.getOrElse(RunnerConfig.DefaultTimeProviderType),
            config.applicationId,
            config.ledgerParty,
            config.compilerConfig,
          )
        } yield ()

        flow.onComplete(_ => system.terminate())

        Await.result(flow, Duration.Inf)
      }
    }
  }
}
