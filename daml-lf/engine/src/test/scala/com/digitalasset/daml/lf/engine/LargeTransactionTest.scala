// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package engine

import java.io.File

import com.daml.bazeltools.BazelRunfiles
import com.daml.lf.archive.{Decode, UniversalArchiveReader}
import com.daml.lf.data.Ref._
import com.daml.lf.data.{FrontStack, ImmArray, Ref, Time}
import com.daml.lf.language.Ast
import com.daml.lf.scenario.ScenarioLedger
import com.daml.lf.transaction.SubmittedTransaction
import com.daml.lf.transaction.Transaction.Transaction
import com.daml.lf.transaction.{Node => N, NodeId, Transaction => Tx}
import com.daml.lf.value.Value
import com.daml.lf.value.Value._
import com.daml.lf.command._
import org.scalameter
import org.scalameter.Quantity
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.language.implicitConversions

class LargeTransactionTest extends AnyWordSpec with Matchers with BazelRunfiles {

  /** Tiny wrapper around ScenarioLedger that provides
    * a mutable API for eas of use in tests.
    */
  class MutableLedger {
    import ScenarioLedger.{initialLedger => _, _}
    private var ledger: ScenarioLedger = initialLedger()

    private def initialLedger(): ScenarioLedger = ScenarioLedger.initialLedger(Time.Timestamp.now())

    def commit(
        submitter: Party,
        effectiveAt: Time.Timestamp,
        tx: SubmittedTransaction,
    ): Transaction =
      ScenarioLedger
        .commitTransaction(
          actAs = Set(submitter),
          readAs = Set.empty,
          effectiveAt = effectiveAt,
          optLocation = None,
          tx = tx,
          l = ledger,
        )
        .fold(
          err => throw new RuntimeException(err.toString),
          result => {
            ledger = result.newLedger
            result.richTransaction.transaction
          },
        )

    def get(submitter: Party, effectiveAt: Time.Timestamp)(
        id: ContractId
    ): Option[ContractInst[VersionedValue[ContractId]]] = {
      ledger.lookupGlobalContract(
        ParticipantView(Set(submitter), Set.empty),
        effectiveAt,
        id,
      ) match {
        case LookupOk(_, coinst, _) => Some(coinst)
        case _: LookupContractNotEffective | _: LookupContractNotActive |
            _: LookupContractNotVisible | _: LookupContractNotFound =>
          None
      }
    }
  }

  private def hash(s: String, i: Int) =
    crypto.Hash.hashPrivateKey(s + ":" + i.toString)

  private val participant = Ref.ParticipantId.assertFromString("participant")

  private def loadPackage(
      resource: String
  ): (PackageId, Ast.Package, Map[PackageId, Ast.Package]) = {
    val payloads = UniversalArchiveReader().readFile(new File(rlocation(resource))).get
    val packages = payloads.all.map(Decode.decodeArchivePayload(_)).toMap
    val mainPkgId = payloads.main.pkgId
    (mainPkgId, packages(mainPkgId), packages)
  }

  private[this] val (largeTxId, largeTxPkg, allPackages) = loadPackage(
    "daml-lf/tests/LargeTransaction.dar"
  )
  private[this] val largeTx = (largeTxId, largeTxPkg)

  private[this] val party = Party.assertFromString("party")

  private def lookupPackage(pkgId: PackageId): Option[Ast.Package] = allPackages.get(pkgId)

  private def report(name: String, quantity: Quantity[Double]): Unit =
    println(s"$name: $quantity")

  private val engine: Engine = Engine.DevEngine()

  List(5000, 50000, 500000)
    .foreach { txSize =>
      val testName = s"create large transaction with one contract containing $txSize Ints"
      testName in {
        report(
          testName,
          testLargeTransactionOneContract(engine)(txSize),
        )
      }
    }

  List(5000, 50000, 500000)
    .foreach { txSize =>
      val testName = s"create large transaction with $txSize small contracts"
      testName in {
        report(
          testName,
          testLargeTransactionManySmallContracts(engine)(txSize),
        )
      }
    }

  List(5000, 50000, 500000)
    .foreach { txSize =>
      val testName = s"execute choice with a List of $txSize Ints"
      testName in {
        report(testName, testLargeChoiceArgument(engine)(txSize))
      }
    }

  private def testLargeTransactionOneContract(engine: Engine)(
      txSize: Int
  ): Quantity[Double] = {
    val ledger = new MutableLedger()
    val rangeOfIntsTemplateId = Identifier(largeTx._1, qn("LargeTransaction:RangeOfInts"))
    val createCmd = rangeOfIntsCreateCmd(rangeOfIntsTemplateId, 0, 1, txSize)
    val createCmdTx =
      submitCommand(ledger, engine)(
        submitter = party,
        cmd = createCmd,
        cmdReference = "create RangeOfInts",
        seed = hash("testLargeTransactionOneContract:create", txSize),
      )
    val contractId = firstRootNode(createCmdTx) match {
      case create: N.NodeCreate[ContractId] => create.coid
      case n => fail(s"Expected NodeCreate, but got: $n")
    }
    val exerciseCmd = toListContainerExerciseCmd(rangeOfIntsTemplateId, contractId)
    val (exerciseCmdTx, quanity) = measureWithResult(
      submitCommand(ledger, engine)(
        submitter = party,
        cmd = exerciseCmd,
        cmdReference = "exercise RangeOfInts.ToListContainer",
        seed = hash("testLargeTransactionOneContract:exercise", txSize),
      )
    )

    assertOneContractWithManyInts(exerciseCmdTx, List.range(0L, txSize.toLong))
    quanity
  }

  private def testLargeTransactionManySmallContracts(engine: Engine)(
      num: Int
  ): Quantity[Double] = {
    val ledger = new MutableLedger()
    val rangeOfIntsTemplateId = Identifier(largeTx._1, qn("LargeTransaction:RangeOfInts"))
    val createCmd = rangeOfIntsCreateCmd(rangeOfIntsTemplateId, 0, 1, num)
    val createCmdTx: Transaction =
      submitCommand(ledger, engine)(
        submitter = party,
        cmd = createCmd,
        cmdReference = "create RangeOfInts",
        seed = hash("testLargeTransactionManySmallContracts:create", num),
      )
    val contractId = firstRootNode(createCmdTx) match {
      case create: N.NodeCreate[ContractId] => create.coid
      case n @ _ => fail(s"Expected NodeCreate, but got: $n")
    }
    val exerciseCmd = toListOfIntContainers(rangeOfIntsTemplateId, contractId)
    val (exerciseCmdTx, quanity) = measureWithResult(
      submitCommand(ledger, engine)(
        submitter = party,
        cmd = exerciseCmd,
        cmdReference = "exercise RangeOfInts.ToListContainer",
        seed = hash("testLargeTransactionManySmallContracts:exercise", num),
      )
    )

    assertManyContractsOneIntPerContract(exerciseCmdTx, num)
    quanity
  }

  private def testLargeChoiceArgument(engine: Engine)(
      size: Int
  ): Quantity[Double] = {
    val ledger = new MutableLedger()
    val listUtilTemplateId = Identifier(largeTx._1, qn("LargeTransaction:ListUtil"))
    val createCmd = listUtilCreateCmd(listUtilTemplateId)
    val createCmdTx: Transaction =
      submitCommand(ledger, engine)(
        submitter = party,
        cmd = createCmd,
        cmdReference = "create ListUtil",
        seed = hash("testLargeChoiceArgument:create", size),
      )
    val contractId = firstRootNode(createCmdTx) match {
      case create: N.NodeCreate[ContractId] => create.coid
      case n @ _ => fail(s"Expected NodeCreate, but got: $n")
    }
    val exerciseCmd = sizeExerciseCmd(listUtilTemplateId, contractId)(size)
    val (exerciseCmdTx, quantity) = measureWithResult(
      submitCommand(ledger, engine)(
        submitter = party,
        cmd = exerciseCmd,
        cmdReference = "exercise ListUtil.Size",
        seed = hash("testLargeTransactionManySmallContracts:exercise", size),
      )
    )

    assertSizeExerciseTransaction(exerciseCmdTx, size.toLong)
    quantity
  }

  private def qn(str: String): QualifiedName = QualifiedName.assertFromString(str)

  private def assertOneContractWithManyInts(
      exerciseCmdTx: Transaction,
      expected: List[Long],
  ): Assertion = {

    val listValue = extractResultFieldFromExerciseTransaction(exerciseCmdTx, "list")

    val list: FrontStack[Value[Value.ContractId]] = listValue match {
      case ValueList(x) => x
      case f @ _ => fail(s"Unexpected match: $f")
    }

    val actual: List[Long] = list.iterator.collect { case ValueInt64(x) => x }.toList
    actual shouldBe expected
  }

  private def assertManyContractsOneIntPerContract(
      exerciseCmdTx: Transaction,
      expectedNumberOfContracts: Int,
  ): Assertion = {

    val newContracts: List[N.GenNode[NodeId, Value.ContractId]] =
      firstRootNode(exerciseCmdTx) match {
        case ne: N.NodeExercises[_, _] => ne.children.toList.map(nid => exerciseCmdTx.nodes(nid))
        case n @ _ => fail(s"Unexpected match: $n")
      }

    newContracts.count {
      case _: N.NodeCreate[ContractId] => true
      case n @ _ => fail(s"Unexpected match: $n")
    } shouldBe expectedNumberOfContracts
  }

  private def submitCommand(ledger: MutableLedger, engine: Engine)(
      submitter: Party,
      cmd: ApiCommand,
      cmdReference: String,
      seed: crypto.Hash,
  ): Transaction = {
    val effectiveAt = Time.Timestamp.now()
    engine
      .submit(
        submitters = Set(submitter),
        readAs = Set.empty,
        Commands(ImmArray(cmd), effectiveAt, cmdReference),
        participant,
        seed,
      )
      .consume(
        ledger.get(submitter, effectiveAt),
        lookupPackage,
        { _ =>
          sys.error("TODO keys for LargeTransactionTest")
        },
      ) match {
      case Left(err) =>
        fail(s"Unexpected error: $err")
      case Right((tx, _)) =>
        ledger.commit(submitter, effectiveAt, tx)
    }
  }

  private def rangeOfIntsCreateCmd(
      templateId: Identifier,
      start: Int,
      step: Int,
      number: Int,
  ): CreateCommand = {
    val fields = ImmArray(
      (Some[Name]("party"), ValueParty(party)),
      (Some[Name]("start"), ValueInt64(start.toLong)),
      (Some[Name]("step"), ValueInt64(step.toLong)),
      (Some[Name]("size"), ValueInt64(number.toLong)),
    )
    CreateCommand(templateId, ValueRecord(Some(templateId), fields))
  }

  private def toListContainerExerciseCmd(
      templateId: Identifier,
      contractId: ContractId,
  ): ExerciseCommand = {
    val choice = "ToListContainer"
    val emptyArgs = ValueRecord(None, ImmArray.empty)
    ExerciseCommand(templateId, contractId, choice, (emptyArgs))
  }

  private def toListOfIntContainers(
      templateId: Identifier,
      contractId: ContractId,
  ): ExerciseCommand = {
    val choice = "ToListOfIntContainers"
    val emptyArgs = ValueRecord(None, ImmArray.empty)
    ExerciseCommand(templateId, contractId, choice, (emptyArgs))
  }

  private def listUtilCreateCmd(templateId: Identifier): CreateCommand = {
    val fields = ImmArray((Some[Name]("party"), ValueParty(party)))
    CreateCommand(templateId, ValueRecord(Some(templateId), fields))
  }

  private def sizeExerciseCmd(templateId: Identifier, contractId: ContractId)(
      size: Int
  ): ExerciseCommand = {
    val choice = "Size"
    val choiceDefRef = Identifier(templateId.packageId, qn(s"LargeTransaction:$choice"))
    val damlList = ValueList(FrontStack(elements = List.range(0L, size.toLong).map(ValueInt64)))
    val choiceArgs = ValueRecord(Some(choiceDefRef), ImmArray((None, damlList)))
    ExerciseCommand(templateId, contractId, choice, choiceArgs)
  }

  private def assertSizeExerciseTransaction(
      exerciseCmdTx: Transaction,
      expected: Long,
  ): Assertion = {

    val value: Value[ContractId] =
      extractResultFieldFromExerciseTransaction(exerciseCmdTx, "value")

    val actual: Long = value match {
      case ValueInt64(x) => x
      case f @ _ => fail(s"Unexpected match: $f")
    }

    actual shouldBe expected
  }

  private def extractResultFieldFromExerciseTransaction(
      exerciseCmdTx: Transaction,
      fieldName: String,
  ): Value[ContractId] = {

    val arg: Value[ContractId] =
      extractResultFromExerciseTransaction(exerciseCmdTx)

    val fields: ImmArray[(Option[String], Value[ContractId])] =
      arg match {
        case ValueRecord(_, x: ImmArray[_]) => x
        case v @ _ => fail(s"Unexpected match: $v")
      }

    val valueField: Option[(Option[String], Value[ContractId])] = fields.find { case (n, _) =>
      n.contains(fieldName)
    }

    valueField match {
      case Some((Some(`fieldName`), x)) => x
      case f @ _ => fail(s"Unexpected match: $f")
    }
  }

  private def extractResultFromExerciseTransaction(
      exerciseCmdTx: Transaction
  ): Value[ContractId] = {

    exerciseCmdTx.roots.length shouldBe 1
    exerciseCmdTx.nodes.size shouldBe 2

    val createNode: N.GenNode[NodeId, ContractId] = firstRootNode(exerciseCmdTx) match {
      case ne: N.NodeExercises[_, _] => exerciseCmdTx.nodes(ne.children.head)
      case n @ _ => fail(s"Unexpected match: $n")
    }

    createNode match {
      case create: N.NodeCreate[ContractId] => create.arg
      case n @ _ => fail(s"Unexpected match: $n")
    }
  }

  private def firstRootNode(tx: Tx.Transaction): Tx.Node = tx.nodes(tx.roots.head)

  private def measureWithResult[R](body: => R): (R, Quantity[Double]) = {
    lazy val result: R = body
    val quantity: Quantity[Double] = scalameter.measure(result)
    (result, quantity)
  }

  private implicit def toChoiceName(s: String): Ref.Name = Name.assertFromString(s)

}
