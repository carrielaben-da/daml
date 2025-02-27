// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.dao.events

import java.io.InputStream

import com.daml.ledger.api.v1.event.{CreatedEvent, ExercisedEvent}
import com.daml.ledger.api.v1.value.{
  Identifier => ApiIdentifier,
  Record => ApiRecord,
  Value => ApiValue,
}
import com.daml.lf.engine.ValueEnricher
import com.daml.lf.ledger.EventId
import com.daml.lf.value.Value.VersionedValue
import com.daml.lf.{engine => LfEngine}
import com.daml.logging.LoggingContext
import com.daml.metrics.Metrics
import com.daml.platform.packages.DeduplicatingPackageLoader
import com.daml.platform.participant.util.LfEngineToApi
import com.daml.platform.store.LfValueTranslationCache
import com.daml.platform.store.dao.events.{
  ChoiceName => LfChoiceName,
  DottedName => LfDottedName,
  Identifier => LfIdentifier,
  ModuleName => LfModuleName,
  PackageId => LfPackageId,
  QualifiedName => LfQualifiedName,
  Value => LfValue,
}
import com.daml.platform.store.serialization.{Compression, ValueSerializer}

import scala.concurrent.{ExecutionContext, Future}

final class LfValueTranslation(
    val cache: LfValueTranslationCache.Cache,
    metrics: Metrics,
    enricherO: Option[LfEngine.ValueEnricher],
    loadPackage: (
        LfPackageId,
        LoggingContext,
    ) => Future[Option[com.daml.daml_lf_dev.DamlLf.Archive]],
) {

  private[this] val packageLoader = new DeduplicatingPackageLoader()

  private def cantSerialize(attribute: String, forContract: ContractId): String =
    s"Cannot serialize $attribute for ${forContract.coid}"

  private def serializeCreateArgOrThrow(
      contractId: ContractId,
      arg: VersionedValue,
  ): Array[Byte] =
    ValueSerializer.serializeValue(
      value = arg,
      errorContext = cantSerialize(attribute = "create argument", forContract = contractId),
    )

  private def serializeCreateArgOrThrow(c: Create): Array[Byte] =
    serializeCreateArgOrThrow(c.coid, c.versionedArg)

  private def serializeNullableKeyOrThrow(c: Create): Option[Array[Byte]] =
    c.versionedKey.map(k =>
      ValueSerializer.serializeValue(
        value = k.key,
        errorContext = cantSerialize(attribute = "key", forContract = c.coid),
      )
    )

  private def serializeExerciseArgOrThrow(e: Exercise): Array[Byte] =
    ValueSerializer.serializeValue(
      value = e.versionedChosenValue,
      errorContext = cantSerialize(attribute = "exercise argument", forContract = e.targetCoid),
    )

  private def serializeNullableExerciseResultOrThrow(e: Exercise): Option[Array[Byte]] =
    e.versionedExerciseResult.map(exerciseResult =>
      ValueSerializer.serializeValue(
        value = exerciseResult,
        errorContext = cantSerialize(attribute = "exercise result", forContract = e.targetCoid),
      )
    )

  def serialize(
      contractId: ContractId,
      contractArgument: VersionedValue,
  ): Array[Byte] = {
    cache.contracts.put(
      key = LfValueTranslationCache.ContractCache.Key(contractId),
      value = LfValueTranslationCache.ContractCache.Value(contractArgument),
    )
    serializeCreateArgOrThrow(contractId, contractArgument)
  }

  def serialize(eventId: EventId, create: Create): (Array[Byte], Option[Array[Byte]]) = {
    cache.events.put(
      key = LfValueTranslationCache.EventCache.Key(eventId),
      value = LfValueTranslationCache.EventCache.Value
        .Create(create.versionedArg, create.versionedKey.map(_.key)),
    )
    cache.contracts.put(
      key = LfValueTranslationCache.ContractCache.Key(create.coid),
      value = LfValueTranslationCache.ContractCache.Value(create.versionedArg),
    )
    (serializeCreateArgOrThrow(create), serializeNullableKeyOrThrow(create))
  }

  def serialize(
      eventId: EventId,
      exercise: Exercise,
  ): (Array[Byte], Option[Array[Byte]]) = {
    cache.events.put(
      key = LfValueTranslationCache.EventCache.Key(eventId),
      value = LfValueTranslationCache.EventCache.Value
        .Exercise(exercise.versionedChosenValue, exercise.versionedExerciseResult),
    )
    (
      serializeExerciseArgOrThrow(exercise),
      serializeNullableExerciseResultOrThrow(exercise),
    )
  }

  private[this] def consumeEnricherResult[V](
      result: LfEngine.Result[V]
  )(implicit
      ec: ExecutionContext,
      loggingContext: LoggingContext,
  ): Future[V] = {
    result match {
      case LfEngine.ResultDone(r) => Future.successful(r)
      case LfEngine.ResultError(e) => Future.failed(new RuntimeException(e.message))
      case LfEngine.ResultNeedPackage(packageId, resume) =>
        packageLoader
          .loadPackage(
            packageId = packageId,
            delegate = packageId => loadPackage(packageId, loggingContext),
            metric = metrics.daml.index.db.translation.getLfPackage,
          )
          .flatMap(pkgO => consumeEnricherResult(resume(pkgO)))
      case result =>
        Future.failed(new RuntimeException(s"Unexpected ValueEnricher result: $result"))
    }
  }

  private[this] def toApiValue(
      value: LfValue,
      verbose: Boolean,
      attribute: => String,
      enrich: LfValue => LfEngine.Result[com.daml.lf.value.Value],
  )(implicit
      ec: ExecutionContext,
      loggingContext: LoggingContext,
  ): Future[ApiValue] = for {
    enrichedValue <-
      if (verbose)
        consumeEnricherResult(enrich(value))
      else
        Future.successful(value.value)
  } yield {
    LfEngineToApi.assertOrRuntimeEx(
      failureContext = s"attempting to deserialize persisted $attribute to value",
      LfEngineToApi
        .lfValueToApiValue(
          verbose = verbose,
          value0 = enrichedValue,
        ),
    )
  }

  private[this] def toApiRecord(
      value: LfValue,
      verbose: Boolean,
      attribute: => String,
      enrich: LfValue => LfEngine.Result[com.daml.lf.value.Value],
  )(implicit
      ec: ExecutionContext,
      loggingContext: LoggingContext,
  ): Future[ApiRecord] = for {
    enrichedValue <-
      if (verbose)
        consumeEnricherResult(enrich(value))
      else
        Future.successful(value.value)
  } yield {
    LfEngineToApi.assertOrRuntimeEx(
      failureContext = s"attempting to deserialize persisted $attribute to record",
      LfEngineToApi
        .lfValueToApiRecord(
          verbose = verbose,
          recordValue = enrichedValue,
        ),
    )
  }

  private[this] def apiIdentifierToDamlLfIdentifier(id: ApiIdentifier): LfIdentifier =
    LfIdentifier(
      LfPackageId.assertFromString(id.packageId),
      LfQualifiedName(
        LfModuleName.assertFromString(id.moduleName),
        LfDottedName.assertFromString(id.entityName),
      ),
    )

  private def eventKey(s: String) =
    LfValueTranslationCache.EventCache.Key(EventId.assertFromString(s))

  private def decompressAndDeserialize(algorithm: Compression.Algorithm, value: InputStream) =
    ValueSerializer.deserializeValue(algorithm.decompress(value))

  private[this] def enricher: ValueEnricher = {
    // Note: LfValueTranslation is used by JdbcLedgerDao for both serialization and deserialization.
    // Sometimes the JdbcLedgerDao is used in a way that it never needs to deserialize data in verbose mode
    // (e.g., the indexer, or some tests). In this case, the enricher is not required.
    enricherO.getOrElse(
      sys.error(
        "LfValueTranslation used to deserialize values in verbose mode without a ValueEnricher"
      )
    )
  }

  def deserialize[E](
      raw: Raw.Created[E],
      verbose: Boolean,
  )(implicit
      ec: ExecutionContext,
      loggingContext: LoggingContext,
  ): Future[CreatedEvent] = {
    // Load the deserialized contract argument and contract key from the cache
    // This returns the values in Daml-LF format.
    val create =
      cache.events
        .getIfPresent(eventKey(raw.partial.eventId))
        .getOrElse(
          LfValueTranslationCache.EventCache.Value.Create(
            argument = decompressAndDeserialize(raw.createArgumentCompression, raw.createArgument),
            key = raw.createKeyValue.map(decompressAndDeserialize(raw.createKeyValueCompression, _)),
          )
        )
        .assertCreate()

    lazy val templateId: LfIdentifier = apiIdentifierToDamlLfIdentifier(raw.partial.templateId.get)

    // Convert Daml-LF values to ledger API values.
    // In verbose mode, this involves loading Daml-LF packages and filling in missing type information.
    for {
      createArguments <- toApiRecord(
        value = create.argument,
        verbose = verbose,
        attribute = "create argument",
        enrich = value => enricher.enrichContract(templateId, value.value),
      )
      contractKey <- create.key match {
        case Some(key) =>
          toApiValue(
            value = key,
            verbose = verbose,
            attribute = "create key",
            enrich = value => enricher.enrichContractKey(templateId, value.value),
          ).map(Some(_))
        case None => Future.successful(None)
      }
    } yield {
      raw.partial.copy(
        createArguments = Some(createArguments),
        contractKey = contractKey,
      )
    }
  }

  def deserialize(
      raw: Raw.TreeEvent.Exercised,
      verbose: Boolean,
  )(implicit
      ec: ExecutionContext,
      loggingContext: LoggingContext,
  ): Future[ExercisedEvent] = {
    // Load the deserialized choice argument and choice result from the cache
    // This returns the values in Daml-LF format.
    val exercise =
      cache.events
        .getIfPresent(eventKey(raw.partial.eventId))
        .getOrElse(
          LfValueTranslationCache.EventCache.Value.Exercise(
            argument =
              decompressAndDeserialize(raw.exerciseArgumentCompression, raw.exerciseArgument),
            result =
              raw.exerciseResult.map(decompressAndDeserialize(raw.exerciseResultCompression, _)),
          )
        )
        .assertExercise()

    lazy val templateId: LfIdentifier = apiIdentifierToDamlLfIdentifier(raw.partial.templateId.get)
    lazy val choiceName: LfChoiceName = LfChoiceName.assertFromString(raw.partial.choice)

    // Convert Daml-LF values to ledger API values.
    // In verbose mode, this involves loading Daml-LF packages and filling in missing type information.
    for {
      choiceArgument <- toApiValue(
        value = exercise.argument,
        verbose = verbose,
        attribute = "exercise argument",
        enrich = value => enricher.enrichChoiceArgument(templateId, choiceName, value.value),
      )
      exerciseResult <- exercise.result match {
        case Some(result) =>
          toApiValue(
            value = result,
            verbose = verbose,
            attribute = "exercise result",
            enrich = value => enricher.enrichChoiceResult(templateId, choiceName, value.value),
          ).map(Some(_))
        case None => Future.successful(None)
      }
    } yield {
      raw.partial.copy(
        choiceArgument = Some(choiceArgument),
        exerciseResult = exerciseResult,
      )
    }
  }
}
