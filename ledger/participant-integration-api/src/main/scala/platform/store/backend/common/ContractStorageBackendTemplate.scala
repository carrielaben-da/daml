// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.backend.common

import java.sql.Connection
import java.time.Instant

import anorm.SqlParser.{array, byteArray, int, long}
import anorm.{ResultSetParser, Row, RowParser, SimpleSql, ~}
import com.daml.lf.data.Ref
import com.daml.platform.store.Conversions.{contractId, instantFromMicros, offset}
import com.daml.platform.store.SimpleSqlAsVectorOf.SimpleSqlAsVectorOf
import com.daml.platform.store.appendonlydao.events.{ContractId, Key}
import com.daml.platform.store.backend.common.ComposableQuery.{CompositeSql, SqlStringInterpolation}
import com.daml.platform.store.backend.{ContractStorageBackend, StorageBackend}
import com.daml.platform.store.cache.StringInterning
import com.daml.platform.store.interfaces.LedgerDaoContractsReader.{
  KeyAssigned,
  KeyState,
  KeyUnassigned,
}

import scala.util.{Failure, Success, Try}

trait ContractStorageBackendTemplate extends ContractStorageBackend {
  import com.daml.platform.store.Conversions.ArrayColumnToIntArray._

  def queryStrategy: QueryStrategy

  override def contractKeyGlobally(key: Key, validAt: Long)(
      connection: Connection
  ): Option[ContractId] =
    contractKey(
      resultColumns = List("contract_id"),
      resultParser = contractId("contract_id"),
    )(
      readersWithStringInterning = None,
      key = key,
      validAt = validAt,
    )(connection)

  private def emptyContractIds: Throwable =
    new IllegalArgumentException(
      "Cannot lookup the maximum ledger time for an empty set of contract identifiers"
    )

  private def notFound(missingContractIds: Set[ContractId]): Throwable =
    new IllegalArgumentException(
      s"The following contracts have not been found: ${missingContractIds.map(_.coid).mkString(", ")}"
    )

  protected def maximumLedgerTimeSqlLiteral(
      id: ContractId,
      lastEventSequentialId: Long,
  ): SimpleSql[Row] = {
    import com.daml.platform.store.Conversions.ContractIdToStatement
    SQL"""
  WITH archival_event AS (
         SELECT participant_events.*
           FROM participant_events
          WHERE contract_id = $id
            AND event_kind = 20  -- consuming exercise
            AND event_sequential_id <= $lastEventSequentialId
          FETCH NEXT 1 ROW ONLY
       ),
       create_event AS (
         SELECT ledger_effective_time
           FROM participant_events
          WHERE contract_id = $id
            AND event_kind = 10  -- create
            AND event_sequential_id <= $lastEventSequentialId
          FETCH NEXT 1 ROW ONLY -- limit here to guide planner wrt expected number of results
       ),
       divulged_contract AS (
         SELECT ledger_effective_time
           FROM participant_events
          WHERE contract_id = $id
            AND event_kind = 0 -- divulgence
            AND event_sequential_id <= $lastEventSequentialId
          ORDER BY event_sequential_id
            -- prudent engineering: make results more stable by preferring earlier divulgence events
            -- Results might still change due to pruning.
          FETCH NEXT 1 ROW ONLY
       ),
       create_and_divulged_contracts AS (
         (SELECT * FROM create_event)   -- prefer create over divulgence events
         UNION ALL
         (SELECT * FROM divulged_contract)
       )
  SELECT ledger_effective_time
    FROM create_and_divulged_contracts
   WHERE NOT EXISTS (SELECT 1 FROM archival_event)
   FETCH NEXT 1 ROW ONLY"""
  }

  // TODO append-only: revisit this approach when doing cleanup, so we can decide if it is enough or not.
  // TODO append-only: consider pulling up traversal logic to upper layer
  override def maximumLedgerTime(
      ids: Set[ContractId],
      lastEventSequentialId: Long,
  )(connection: Connection): Try[Option[Instant]] = {
    if (ids.isEmpty) {
      Failure(emptyContractIds)
    } else {
      def lookup(id: ContractId): Option[Option[Instant]] =
        maximumLedgerTimeSqlLiteral(id, lastEventSequentialId).as(
          instantFromMicros("ledger_effective_time").?.singleOpt
        )(
          connection
        )

      val queriedIds: List[(ContractId, Option[Option[Instant]])] = ids.toList
        .map(id => id -> lookup(id))
      val foundLedgerEffectiveTimes: List[Option[Instant]] = queriedIds
        .collect { case (_, Some(found)) =>
          found
        }
      if (foundLedgerEffectiveTimes.size != ids.size) {
        val missingIds = queriedIds.collect { case (missingId, None) =>
          missingId
        }
        Failure(notFound(missingIds.toSet))
      } else Success(foundLedgerEffectiveTimes.max)
    }
  }

  override def keyState(
      key: Key,
      validAt: Long,
      stringInterning: StringInterning,
  )(connection: Connection): KeyState =
    contractKey(
      resultColumns = List("contract_id", "flat_event_witnesses"),
      resultParser = (
        contractId("contract_id")
          ~ array[Int]("flat_event_witnesses")
      ).map { case cId ~ stakeholders =>
        KeyAssigned(
          cId,
          stakeholders.view.map(stringInterning.party.interned).toSet,
        )
      },
    )(
      readersWithStringInterning = None,
      key = key,
      validAt = validAt,
    )(connection).getOrElse(KeyUnassigned)

  private val fullDetailsContractRowParser
      : RowParser[StringInterning => StorageBackend.RawContractState] =
    (int("template_id").?
      ~ array[Int]("flat_event_witnesses")
      ~ byteArray("create_argument").?
      ~ int("create_argument_compression").?
      ~ int("event_kind") ~ instantFromMicros("ledger_effective_time").?)
      .map {
        case internedTemplateId ~ flatEventWitnesses ~ createArgument ~ createArgumentCompression ~ eventKind ~ ledgerEffectiveTime =>
          stringInterning =>
            StorageBackend.RawContractState(
              templateId = internedTemplateId.map(stringInterning.templateId.unsafe.interned),
              flatEventWitnesses = flatEventWitnesses.view
                .map(stringInterning.party.interned)
                .toSet,
              createArgument = createArgument,
              createArgumentCompression = createArgumentCompression,
              eventKind = eventKind,
              ledgerEffectiveTime = ledgerEffectiveTime,
            )
      }

  override def contractState(
      contractId: ContractId,
      before: Long,
      stringInterning: StringInterning,
  )(
      connection: Connection
  ): Option[StorageBackend.RawContractState] = {
    import com.daml.platform.store.Conversions.ContractIdToStatement
    SQL"""
           SELECT
             template_id,
             flat_event_witnesses,
             create_argument,
             create_argument_compression,
             event_kind,
             ledger_effective_time
           FROM participant_events
           WHERE
             contract_id = $contractId
             AND event_sequential_id <= $before
             AND (event_kind = 10 OR event_kind = 20)
           ORDER BY event_sequential_id DESC
           FETCH NEXT 1 ROW ONLY"""
      .as(fullDetailsContractRowParser.singleOpt)(connection)
      .map(_(stringInterning))
  }

  private val contractStateRowParser
      : RowParser[StringInterning => StorageBackend.RawContractStateEvent] =
    (int("event_kind") ~
      contractId("contract_id") ~
      int("template_id").? ~
      instantFromMicros("ledger_effective_time").? ~
      byteArray("create_key_value").? ~
      int("create_key_value_compression").? ~
      byteArray("create_argument").? ~
      int("create_argument_compression").? ~
      long("event_sequential_id") ~
      array[Int]("flat_event_witnesses") ~
      offset("event_offset")).map {
      case eventKind ~ contractId ~ templateId ~ ledgerEffectiveTime ~ createKeyValue ~ createKeyCompression ~ createArgument ~ createArgumentCompression ~ eventSequentialId ~ flatEventWitnesses ~ offset =>
        stringInterning =>
          StorageBackend.RawContractStateEvent(
            eventKind,
            contractId,
            templateId.map(stringInterning.templateId.interned),
            ledgerEffectiveTime,
            createKeyValue,
            createKeyCompression,
            createArgument,
            createArgumentCompression,
            flatEventWitnesses.view
              .map(stringInterning.party.interned)
              .toSet,
            eventSequentialId,
            offset,
          )
    }

  override def contractStateEvents(
      startExclusive: Long,
      endInclusive: Long,
      stringInterning: StringInterning,
  )(
      connection: Connection
  ): Vector[StorageBackend.RawContractStateEvent] =
    SQL"""
           SELECT
               event_kind,
               contract_id,
               template_id,
               create_key_value,
               create_key_value_compression,
               create_argument,
               create_argument_compression,
               flat_event_witnesses,
               ledger_effective_time,
               event_sequential_id,
               event_offset
           FROM
               participant_events
           WHERE
               event_sequential_id > $startExclusive
               and event_sequential_id <= $endInclusive
               and (event_kind = 10 or event_kind = 20)
           ORDER BY event_sequential_id ASC"""
      .asVectorOf(contractStateRowParser)(connection)
      .map(_(stringInterning))

  private val contractRowParser: RowParser[StringInterning => StorageBackend.RawContract] =
    (int("template_id")
      ~ byteArray("create_argument")
      ~ int("create_argument_compression").?)
      .map { case internedTemplateId ~ createArgument ~ createArgumentCompression =>
        stringInterning =>
          new StorageBackend.RawContract(
            templateId = stringInterning.templateId.unsafe.interned(internedTemplateId),
            createArgument = createArgument,
            createArgumentCompression = createArgumentCompression,
          )
      }
  protected def activeContractSqlLiteral(
      contractId: ContractId,
      treeEventWitnessesClause: CompositeSql,
      resultColumns: List[String],
      coalescedColumns: String,
      lastEventSequentialId: Long,
  ): SimpleSql[Row] = {
    import com.daml.platform.store.Conversions.ContractIdToStatement
    SQL"""  WITH archival_event AS (
               SELECT participant_events.*
                 FROM participant_events
                WHERE contract_id = $contractId
                  AND event_kind = 20  -- consuming exercise
                  AND event_sequential_id <= $lastEventSequentialId
                  AND $treeEventWitnessesClause  -- only use visible archivals
                FETCH NEXT 1 ROW ONLY
             ),
             create_event AS (
               SELECT contract_id, #${resultColumns.mkString(", ")}
                 FROM participant_events
                WHERE contract_id = $contractId
                  AND event_kind = 10  -- create
                  AND event_sequential_id <= $lastEventSequentialId
                  AND $treeEventWitnessesClause
                FETCH NEXT 1 ROW ONLY -- limit here to guide planner wrt expected number of results
             ),
             -- no visibility check, as it is used to backfill missing template_id and create_arguments for divulged contracts
             create_event_unrestricted AS (
               SELECT contract_id, #${resultColumns.mkString(", ")}
                 FROM participant_events
                WHERE contract_id = $contractId
                  AND event_kind = 10  -- create
                  AND event_sequential_id <= $lastEventSequentialId
                FETCH NEXT 1 ROW ONLY -- limit here to guide planner wrt expected number of results
             ),
             divulged_contract AS (
               SELECT divulgence_events.contract_id,
                      -- Note: the divulgence_event.template_id can be NULL
                      -- for certain integrations. For example, the KV integration exploits that
                      -- every participant node knows about all create events. The integration
                      -- therefore only communicates the change in visibility to the IndexDB, but
                      -- does not include a full divulgence event.
                      #$coalescedColumns
                 FROM participant_events divulgence_events LEFT OUTER JOIN create_event_unrestricted ON (divulgence_events.contract_id = create_event_unrestricted.contract_id)
                WHERE divulgence_events.contract_id = $contractId -- restrict to aid query planner
                  AND divulgence_events.event_kind = 0 -- divulgence
                  AND divulgence_events.event_sequential_id <= $lastEventSequentialId
                  AND $treeEventWitnessesClause
                ORDER BY divulgence_events.event_sequential_id
                  -- prudent engineering: make results more stable by preferring earlier divulgence events
                  -- Results might still change due to pruning.
                FETCH NEXT 1 ROW ONLY
             ),
             create_and_divulged_contracts AS (
               (SELECT * FROM create_event)   -- prefer create over divulgence events
               UNION ALL
               (SELECT * FROM divulged_contract)
             )
        SELECT contract_id, #${resultColumns.mkString(", ")}
          FROM create_and_divulged_contracts
         WHERE NOT EXISTS (SELECT 1 FROM archival_event)
         FETCH NEXT 1 ROW ONLY"""
  }

  private def activeContract[T](
      resultSetParser: ResultSetParser[T],
      resultColumns: List[String],
  )(
      readers: Set[Ref.Party],
      contractId: ContractId,
      lastEventSequentialId: Long,
      stringInterning: StringInterning,
  )(connection: Connection): T = {
    val treeEventWitnessesClause: CompositeSql =
      queryStrategy.arrayIntersectionNonEmptyClause(
        columnName = "tree_event_witnesses",
        parties = readers,
        stringInterning = stringInterning,
      )
    val coalescedColumns: String = resultColumns
      .map(columnName =>
        s"COALESCE(divulgence_events.$columnName, create_event_unrestricted.$columnName)"
      )
      .mkString(", ")
    activeContractSqlLiteral(
      contractId,
      treeEventWitnessesClause,
      resultColumns,
      coalescedColumns,
      lastEventSequentialId,
    )
      .as(resultSetParser)(connection)
  }

  private val contractWithoutValueRowParser: RowParser[Int] =
    int("template_id")

  override def activeContractWithArgument(
      readers: Set[Ref.Party],
      contractId: ContractId,
      lastEventSequentialId: Long,
      stringInterning: StringInterning,
  )(connection: Connection): Option[StorageBackend.RawContract] = {
    activeContract(
      resultSetParser = contractRowParser.singleOpt,
      resultColumns = List("template_id", "create_argument", "create_argument_compression"),
    )(
      readers = readers,
      contractId = contractId,
      lastEventSequentialId = lastEventSequentialId,
      stringInterning = stringInterning,
    )(connection).map(_(stringInterning))
  }

  override def activeContractWithoutArgument(
      readers: Set[Ref.Party],
      contractId: ContractId,
      lastEventSequentialId: Long,
      stringInterning: StringInterning,
  )(connection: Connection): Option[String] =
    activeContract(
      resultSetParser = contractWithoutValueRowParser.singleOpt,
      resultColumns = List("template_id"),
    )(
      readers = readers,
      contractId = contractId,
      lastEventSequentialId = lastEventSequentialId,
      stringInterning = stringInterning,
    )(connection).map(stringInterning.templateId.unsafe.interned)

  override def contractKey(
      readers: Set[Ref.Party],
      key: Key,
      validAt: Long,
      stringInterning: StringInterning,
  )(
      connection: Connection
  ): Option[ContractId] =
    contractKey(
      resultColumns = List("contract_id"),
      resultParser = contractId("contract_id"),
    )(
      readersWithStringInterning = Some(readers -> stringInterning),
      key = key,
      validAt = validAt,
    )(connection)

  private def contractKey[T](
      resultColumns: List[String],
      resultParser: RowParser[T],
  )(
      readersWithStringInterning: Option[(Set[Ref.Party], StringInterning)],
      key: Key,
      validAt: Long,
  )(
      connection: Connection
  ): Option[T] = {
    def withAndIfNonEmptyReaders(
        queryF: (Set[Ref.Party], StringInterning) => CompositeSql
    ): CompositeSql =
      readersWithStringInterning match {
        case Some((readers, stringInterning)) =>
          cSQL"${queryF(readers, stringInterning)} AND"

        case None =>
          cSQL""
      }

    val lastContractKeyFlatEventWitnessesClause =
      withAndIfNonEmptyReaders(
        queryStrategy.arrayIntersectionNonEmptyClause(
          columnName = "last_contract_key_create.flat_event_witnesses",
          _,
          _,
        )
      )
    val participantEventsFlatEventWitnessesClause =
      withAndIfNonEmptyReaders(
        queryStrategy.arrayIntersectionNonEmptyClause(
          columnName = "participant_events.flat_event_witnesses",
          _,
          _,
        )
      )

    import com.daml.platform.store.Conversions.HashToStatement
    SQL"""
         WITH last_contract_key_create AS (
                SELECT participant_events.*
                  FROM participant_events
                 WHERE event_kind = 10 -- create
                   AND create_key_hash = ${key.hash}
                       -- do NOT check visibility here, as otherwise we do not abort the scan early
                   AND event_sequential_id <= $validAt
                 ORDER BY event_sequential_id DESC
                 FETCH NEXT 1 ROW ONLY
              )
         SELECT #${resultColumns.mkString(", ")}
           FROM last_contract_key_create -- creation only, as divulged contracts cannot be fetched by key
         WHERE $lastContractKeyFlatEventWitnessesClause -- check visibility only here
           NOT EXISTS       -- check no archival visible
                (SELECT 1
                   FROM participant_events
                  WHERE event_kind = 20 AND -- consuming exercise
                    $participantEventsFlatEventWitnessesClause
                    contract_id = last_contract_key_create.contract_id
                    AND event_sequential_id <= $validAt
                )"""
      .as(resultParser.singleOpt)(connection)
  }
}
