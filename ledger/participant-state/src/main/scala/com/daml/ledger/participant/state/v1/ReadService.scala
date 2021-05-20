// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.v1

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.api.health.ReportsHealth
import com.daml.ledger.participant.state.v1.{LedgerInitialConditions, Offset}

/** An interface for reading the state of a ledger participant.
  *
  * The state of a ledger participant is communicated as a stream of state
  * [[Update]]s. That stream is accessible via [[ReadService!.stateUpdates]].
  * Commonly that stream is processed by a single consumer that keeps track of
  * the current state and creates indexes to satisfy read requests against
  * that state.
  *
  * See [[com.daml.ledger.participant.state.v1]] for further architectural
  * information. See [[Update]] for a description of the state updates
  * communicated by [[ReadService!.stateUpdates]].
  */
trait ReadService extends ReportsHealth {

  /** Retrieve the static initial conditions of the ledger, containing
    * the ledger identifier and the initial ledger record time.
    *
    * Returns a single element Source since the implementation may need to
    * first establish connectivity to the underlying ledger. The implementer
    * may assume that this method is called only once, or very rarely.
    * Source is being used instead of Future as this is in line with [[stateUpdates]],
    * and is easy to implement from both Java and Scala.
    */
  def getLedgerInitialConditions(): Source[LedgerInitialConditions, NotUsed]

  /** Get the stream of state [[Update]]s starting from the beginning or right
    * after the given [[Offset]]
    *
    * This is where the meat of the implementation effort lies. Please take your time
    * to read carefully through the properties required from correct implementations.
    * These properties fall into two categories:
    *
    * 1. properties about the sequence of [[(Offset, Update)]] tuples
    *    in a stream read from the beginning, and
    * 2. properties relating the streams obtained from two separate calls
    *   to [[ReadService.stateUpdates]].
    *
    * The first class of properties are invariants of a single stream:
    *
    * - *strictly increasing [[Offset]]s*:
    *   for any two consecutive tuples `(o1, u1)` and `(o2, u2)`, `o1` is
    *   strictly smaller than `o2`.
    *
    * - *initialize before transaction acceptance*: before any
    *   [[Update.TransactionAccepted]], there is a [[Update.ConfigurationChanged]] update
    *   and [[Update.PublicPackageUpload]] updates for all packages referenced by
    *   the [[Update.TransactionAccepted]].
    *
    * - *causal monotonicity*: given a [[Update.TransactionAccepted]] with an associated
    *   ledger time `lt_tx`, it holds that `lt_tx >= lt_c` for all `c`, where `c` is a
    *   contract used by the transaction and `lt_c` the ledger time of the
    *   [[Update.TransactionAccepted]] that created the contract.
    *   The ledger time of a transaction is specified in the corresponding [[TransactionMeta]]
    *   meta-data.
    *   Note that the ledger time of unrelated updates is not necessarily monotonically
    *   increasing.
    *   The creating transaction need not have a [[Update.TransactionAccepted]] even on this participant
    *   if the participant does not host a stakeholder of the contract, e.g., in the case of divulgence.
    *
    * - *time skew*: given a [[Update.TransactionAccepted]] with an associated
    *   ledger time `lt_tx` and a record time `rt_tx`, it holds that
    *   `rt_TX - minSkew <= lt_TX <= rt_TX + maxSkew`, where `minSkew` and `maxSkew`
    *   are parameters specified in the ledger [[com.daml.ledger.participant.state.v1.TimeModel]].
    *
    * - *command deduplication*: Let there be a [[Update.TransactionAccepted]] with [[SubmitterInfo]]
    *   or a [[Update.CommandRejected]] with [[SubmitterInfo]] and not [[Update.CommandRejected.cancelled]] at offset `off2`
    *   and let `off1` be the completion offset where the [[SubmitterInfo.deduplicationPeriod]] starts.
    *   Then there is no other [[Update.TransactionAccepted]] with [[SubmitterInfo]] for the same [[SubmitterInfo.changeId]]
    *   between the offsets `off1` and `off2` inclusive.
    *
    *   So if a command submission has resulted in a [[Update.TransactionAccepted]],
    *   other command submissions with the same [[SubmitterInfo.changeId]] must be deduplicated
    *   if the earlier's [[Update.TransactionAccepted]] falls within the latter's [[SubmitterInfo.deduplicationPeriod]].
    *
    *   Implementations MAY extend the deduplication period arbitrarily and reject a command submission as a duplicate
    *   even if its deduplication period does not include the earlier's [[Update.TransactionAccepted]].
    *   A [[Update.CommandRejected]] completion does not trigger deduplication and implementations SHOULD
    *   process such resubmissions normally, subject to the submission rank guarantee listed below.
    *
    * - *submission rank*: Let there be a [[Update.TransactionAccepted]] with [[SubmitterInfo]]
    *   or a [[Update.CommandRejected]] with [[SubmitterInfo]] and not [[Update.CommandRejected.cancelled]] at offset `off`.
    *   Let `rank` be the [[SubmitterInfo.submissionRank]] of the [[Update]].
    *   Then there is no other [[Update.TransactionAccepted]] or [[Update.CommandRejected]] with [[SubmitterInfo]]
    *   for the same [[SubmitterInfo.changeId]] with offset at least `off`
    *   whose [[SubmitterInfo.submissionRank]] is at most `rank`.
    *
    *   If the [[WriteService]] detects that a command submission would violate the submission rank guarantee
    *   if accepted or rejected, it either returns a [[SubmissionResult.SynchronousError]] error or
    *   produces a [[Update.CommandRejected]] with [[Update.CommandRejected.cancelled]].
    *
    * - *finality*: If the corresponding [[WriteService]] acknowledges a submitted transaction or rejection
    *   with [[SubmissionResult.Acknowledged]], the [[ReadService]] SHOULD make sure that
    *   it eventually produces a [[Update.TransactionAccepted]] or [[Update.CommandRejected]] with this [[SubmitterInfo]],
    *   even if there are crashes or lost network messages.
    *
    * The second class of properties relates multiple calls to
    * [[stateUpdates]]s, and thereby provides constraints on which [[Update]]s
    * need to be persisted. Before explaining them in detail we provide
    * intuition.
    *
    * All [[Update]]s other than [[Update.CommandRejected]] must
    * always be persisted by the backends implementing the [[ReadService]].
    * For rejections, the situation is more nuanced, as we want to provide
    * the backends with additional implementation leeway.
    *
    * TODO(v2) Do we actually exploit this freedom anywhere? With later [[Update.CommandRejected]]s referring
    *  to earlier completion offsets (e.g., ALREADY_EXISTS), this becomes tricky to specify.
    *
    * [[Update.CommandRejected]] messages are advisory messages to submitters of
    * transactions to inform them in a timely fashion that their transaction
    * has been rejected.
    *
    * Given this intuition for the desired mechanism, we advise participant
    * state implementations to aim to always provide timely
    * [[Update.CommandRejected]] messages.
    *
    * Implementations are free to not persist [[Update.CommandRejected]] updates
    * provided their [[Offset]]s are not reused. This is relevant for the case
    * where a consumer rebuilds his view of the state by starting from a fresh
    * call to [[ReadService.stateUpdates]]; e.g., because it or the
    * stream provider crashed.
    *
    * Formally, we capture the expected relation between two calls
    * `s1 = stateUpdates(o1)` and `s2 = stateUpdates(o2)` for `o1 <= o2` as
    * follows.
    *
    * - *unique offsets*: for any update `u1` with offset `uo` in `s1` and any
    *   update `u2` with the same offset `uo` in `se2` it holds that `u1 == u2`.
    *   This means that offsets can never be reused. Together with
    *   *strictly increasing [[Offset]]* this also implies that the order of
    *   elements present in both `s1` and `s2` cannot change.
    *
    * - *persistent updates*: any update other than
    *   [[Update.CommandRejected]] in `s2` must also be present in `s1`.
    *
    * Last but not least, there is an expectation about the relation between streams visible
    * on *separate* participant state implementations connected to the same ledger.
    * The expectation is that two parties hosted on separate participant nodes are in sync
    * on transaction nodes and contracts that they can both see. The more formal definition
    * is based on the notion of projections of transactions
    * (see https://docs.daml.com/concepts/ledger-model/ledger-privacy.html), as follows.
    *
    * Assume that there is
    * - a party `A` hosted at participant `p1`,
    * - a party `B` hosted at participant `p2`, and
    * - an accepted transaction with identifier `tid` evidenced to both participants `p1` and `p2`
    *   in their state update streams after the [[Update.PartyAddedToParticipant]] updates for
    *   `A`, respectively `B`.
    * The projections of `tx1` and `tx2` to the nodes visible to both `A` and `B` is the same.
    *
    * Note that the transaction `tx1` associated to `tid` on `p1` is not required to be the same as
    * the transaction `tx2` associated to `tid` on `p2`, as these two participants do not necessarily
    * host the same parties; and some implementations ensure data segregation on the ledger. Requiring
    * only the projections to sets of parties to be equal leaves just enough leeway for this
    * data segregation.
    *
    *Note further that the offsets of the transactions might not agree, as these offsets are participant-local.
    */
  def stateUpdates(beginAfter: Option[Offset]): Source[(Offset, Update), NotUsed]
}
