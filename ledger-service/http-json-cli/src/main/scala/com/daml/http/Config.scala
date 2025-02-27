// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.http

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import akka.stream.ThrottleMode
import com.daml.dbutils.ConfigCompanion
import com.daml.ledger.api.tls.TlsConfiguration
import scalaz.std.either._
import scalaz.Show
import scalaz.StateT.liftM

import scala.concurrent.duration._

import ch.qos.logback.classic.{Level => LogLevel}
import com.daml.cliopts.Logging.LogEncoder
import com.daml.metrics.MetricsReporter
import com.daml.http.dbbackend.JdbcConfig

// The internal transient scopt structure *and* StartSettings; external `start`
// users should extend StartSettings or DefaultStartSettings themselves
private[http] final case class Config(
    ledgerHost: String,
    ledgerPort: Int,
    address: String = com.daml.cliopts.Http.defaultAddress,
    httpPort: Int,
    portFile: Option[Path] = None,
    packageReloadInterval: FiniteDuration = StartSettings.DefaultPackageReloadInterval,
    packageMaxInboundMessageSize: Option[Int] = None,
    maxInboundMessageSize: Int = StartSettings.DefaultMaxInboundMessageSize,
    healthTimeoutSeconds: Int = StartSettings.DefaultHealthTimeoutSeconds,
    tlsConfig: TlsConfiguration = TlsConfiguration(enabled = false, None, None, None),
    jdbcConfig: Option[JdbcConfig] = None,
    staticContentConfig: Option[StaticContentConfig] = None,
    allowNonHttps: Boolean = false,
    wsConfig: Option[WebsocketConfig] = None,
    nonRepudiation: nonrepudiation.Configuration.Cli = nonrepudiation.Configuration.Cli.Empty,
    logLevel: Option[LogLevel] = None, // the default is in logback.xml
    logEncoder: LogEncoder = LogEncoder.Plain,
    metricsReporter: Option[MetricsReporter] = None,
    metricsReportingInterval: FiniteDuration = 10 seconds,
    surrogateTpIdCacheMaxEntries: Option[Long] = None,
) extends StartSettings

private[http] object Config {
  import scala.language.postfixOps
  val Empty = Config(ledgerHost = "", ledgerPort = -1, httpPort = -1)
  val DefaultWsConfig =
    WebsocketConfig(
      maxDuration = 120 minutes,
      throttleElem = 20,
      throttlePer = 1 second,
      maxBurst = 20,
      ThrottleMode.Shaping,
      heartBeatPer = 5 second,
    )
}

// It is public for Daml Hub
final case class WebsocketConfig(
    maxDuration: FiniteDuration,
    throttleElem: Int,
    throttlePer: FiniteDuration,
    maxBurst: Int,
    mode: ThrottleMode,
    heartBeatPer: FiniteDuration,
)

private[http] object WebsocketConfig
    extends ConfigCompanion[WebsocketConfig, DummyImplicit]("WebsocketConfig") {

  implicit val showInstance: Show[WebsocketConfig] = Show.shows(c =>
    s"WebsocketConfig(maxDuration=${c.maxDuration}, heartBeatPer=${c.heartBeatPer}.seconds)"
  )

  lazy val help: String =
    "Contains comma-separated key-value pairs. Where:\n" +
      s"${indent}maxDuration -- Maximum websocket session duration in minutes\n" +
      s"${indent}heartBeatPer -- Server-side heartBeat interval in seconds\n" +
      s"${indent}Example: " + helpString("120", "5")

  lazy val usage: String = helpString(
    "<Maximum websocket session duration in minutes>",
    "Server-side heartBeat interval in seconds",
  )

  protected[this] override def create(implicit readCtx: DummyImplicit) =
    for {
      md <- optionalLongField("maxDuration")
      hbp <- optionalLongField("heartBeatPer")
    } yield Config.DefaultWsConfig
      .copy(
        maxDuration = md
          .map(t => FiniteDuration(t, TimeUnit.MINUTES))
          .getOrElse(Config.DefaultWsConfig.maxDuration),
        heartBeatPer = hbp
          .map(t => FiniteDuration(t, TimeUnit.SECONDS))
          .getOrElse(Config.DefaultWsConfig.heartBeatPer),
      )

  private def helpString(maxDuration: String, heartBeatPer: String): String =
    s"""\"maxDuration=$maxDuration,heartBeatPer=$heartBeatPer\""""
}

private[http] final case class StaticContentConfig(
    prefix: String,
    directory: File,
)

private[http] object StaticContentConfig
    extends ConfigCompanion[StaticContentConfig, DummyImplicit]("StaticContentConfig") {

  implicit val showInstance: Show[StaticContentConfig] =
    Show.shows(a => s"StaticContentConfig(prefix=${a.prefix}, directory=${a.directory})")

  lazy val help: String =
    "Contains comma-separated key-value pairs. Where:\n" +
      s"${indent}prefix -- URL prefix,\n" +
      s"${indent}directory -- local directory that will be mapped to the URL prefix.\n" +
      s"${indent}Example: " + helpString("static", "./static-content")

  lazy val usage: String = helpString("<URL prefix>", "<directory>")

  protected[this] override def create(implicit readCtx: DummyImplicit) =
    for {
      prefix <- requiredField("prefix").flatMap(p => liftM(prefixCantStartWithSlash(p)))
      directory <- requiredDirectoryField("directory")
    } yield StaticContentConfig(prefix, directory)

  private def prefixCantStartWithSlash(s: String): Either[String, String] =
    if (s.startsWith("/")) Left(s"prefix cannot start with slash: $s")
    else Right(s)

  private def helpString(prefix: String, directory: String): String =
    s"""\"prefix=$prefix,directory=$directory\""""
}
