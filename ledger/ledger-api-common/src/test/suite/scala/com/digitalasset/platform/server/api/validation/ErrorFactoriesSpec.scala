// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml

import error.{ContextualizedErrorLogger, DamlContextualizedErrorLogger, ErrorCodesVersionSwitcher}
import ledger.api.domain.LedgerId
import logging.{ContextualizedLogger, LoggingContext}
import platform.server.api.validation.ErrorFactories
import platform.server.api.validation.ErrorFactories._

import com.google.protobuf
import com.google.rpc._
import io.grpc.Status.Code
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.StatusProto
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

class ErrorFactoriesSpec extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {
  private val correlationId = "trace-id"
  private val logger = ContextualizedLogger.get(getClass)
  private val loggingContext = LoggingContext.ForTesting

  private implicit val contextualizedErrorLogger: ContextualizedErrorLogger =
    new DamlContextualizedErrorLogger(logger, loggingContext, Some(correlationId))

  private val DefaultTraceIdRequestInfo: ErrorDetails.RequestInfoDetail =
    ErrorDetails.RequestInfoDetail("trace-id")

  "ErrorFactories" should {

    "return malformedPackageId" in {
      assertVersionedError(
        _.malformedPackageId(request = "request123", message = "message123")(
          contextualizedErrorLogger = contextualizedErrorLogger,
          logger = logger,
          loggingContext = loggingContext,
        )
      )(
        v1_code = Code.INVALID_ARGUMENT,
        v1_message = "message123",
        v1_details = Seq.empty,
        v2_code = Code.INVALID_ARGUMENT,
        v2_message = s"MALFORMED_PACKAGE_ID(8,$correlationId): message123",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("MALFORMED_PACKAGE_ID"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return packageNotFound" in {
      assertVersionedError(_.packageNotFound("packageId123"))(
        v1_code = Code.NOT_FOUND,
        v1_message = "",
        v1_details = Seq.empty,
        v2_code = Code.NOT_FOUND,
        v2_message = s"PACKAGE_NOT_FOUND(11,$correlationId): Could not find package.",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("PACKAGE_NOT_FOUND"),
          DefaultTraceIdRequestInfo,
          ErrorDetails.ResourceInfoDetail("PACKAGE", "packageId123"),
        ),
      )
    }

    "return the DuplicateCommandException" in {
      assertVersionedError(_.duplicateCommandException)(
        v1_code = Code.ALREADY_EXISTS,
        v1_message = "Duplicate command",
        v1_details = Seq(definiteAnswers(false)),
        v2_code = Code.ALREADY_EXISTS,
        v2_message =
          s"DUPLICATE_COMMAND(10,$correlationId): A command with the given command id has already been successfully processed",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("DUPLICATE_COMMAND"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return a permissionDenied error" in {
      assertVersionedError(_.permissionDenied("some cause"))(
        v1_code = Code.PERMISSION_DENIED,
        v1_message = "",
        v1_details = Seq.empty,
        v2_code = Code.PERMISSION_DENIED,
        v2_message =
          s"An error occurred. Please contact the operator and inquire about the request $correlationId",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("PERMISSION_DENIED"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return an unauthenticatedMissingJwtToken error" in {
      assertVersionedError(_.unauthenticatedMissingJwtToken())(
        v1_code = Code.UNAUTHENTICATED,
        v1_message = "",
        v1_details = Seq.empty,
        v2_code = Code.UNAUTHENTICATED,
        v2_message =
          s"An error occurred. Please contact the operator and inquire about the request $correlationId",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("UNAUTHENTICATED"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return an internalAuthenticationError" in {
      val someSecuritySafeMessage = "nothing security sensitive in here"
      val someThrowable = new RuntimeException("some internal authentication error")
      assertVersionedError(_.internalAuthenticationError(someSecuritySafeMessage, someThrowable))(
        v1_code = Code.INTERNAL,
        v1_message = someSecuritySafeMessage,
        v1_details = Seq.empty,
        v2_code = Code.INTERNAL,
        v2_message =
          s"An error occurred. Please contact the operator and inquire about the request $correlationId",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("INTERNAL_AUTHORIZATION_ERROR"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return a missingLedgerConfig error" in {
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        assertVersionedError(_.missingLedgerConfig(definiteAnswer))(
          v1_code = Code.UNAVAILABLE,
          v1_message = "The ledger configuration is not available.",
          v1_details = expectedDetails,
          v2_code = Code.NOT_FOUND,
          v2_message =
            s"LEDGER_CONFIGURATION_NOT_FOUND(11,$correlationId): The ledger configuration is not available.",
          v2_details = Seq[ErrorDetails.ErrorDetail](
            ErrorDetails.ErrorInfoDetail("LEDGER_CONFIGURATION_NOT_FOUND"),
            DefaultTraceIdRequestInfo,
          ),
        )
      }
    }

    "return an aborted error" in {
      // TODO error codes: This error code is not specific enough.
      //                   Break down into more specific errors.
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        val exception = aborted("my message", definiteAnswer)
        val status = StatusProto.fromThrowable(exception)
        status.getCode shouldBe Code.ABORTED.value()
        status.getMessage shouldBe "my message"
        status.getDetailsList.asScala shouldBe expectedDetails
      }
    }

    "return an invalidField error" in {
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        assertVersionedError(_.invalidField("my field", "my message", definiteAnswer))(
          v1_code = Code.INVALID_ARGUMENT,
          v1_message = "Invalid field my field: my message",
          v1_details = expectedDetails,
          v2_code = Code.INVALID_ARGUMENT,
          v2_message =
            s"INVALID_FIELD(8,$correlationId): The submitted command has a field with invalid value: Invalid field my field: my message",
          v2_details = Seq[ErrorDetails.ErrorDetail](
            ErrorDetails.ErrorInfoDetail("INVALID_FIELD"),
            DefaultTraceIdRequestInfo,
          ),
        )
      }
    }

    "return a ledgerIdMismatch error" in {
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        assertVersionedError(
          _.ledgerIdMismatch(LedgerId("expected"), LedgerId("received"), definiteAnswer)
        )(
          v1_code = Code.NOT_FOUND,
          v1_message = "Ledger ID 'received' not found. Actual Ledger ID is 'expected'.",
          v1_details = expectedDetails,
          v2_code = Code.NOT_FOUND,
          v2_message =
            s"LEDGER_ID_MISMATCH(11,$correlationId): Ledger ID 'received' not found. Actual Ledger ID is 'expected'.",
          v2_details = Seq[ErrorDetails.ErrorDetail](
            ErrorDetails.ErrorInfoDetail("LEDGER_ID_MISMATCH"),
            DefaultTraceIdRequestInfo,
          ),
        )
      }
    }

    "fail on creating a ledgerIdMismatch error due to a wrong definite answer" in {
      an[IllegalArgumentException] should be thrownBy ledgerIdMismatch(
        LedgerId("expected"),
        LedgerId("received"),
        definiteAnswer = Some(true),
      )
    }

    "return a participantPrunedDataAccessed error" in {
      assertVersionedError(_.participantPrunedDataAccessed("my message"))(
        v1_code = Code.NOT_FOUND,
        v1_message = "my message",
        v1_details = Seq.empty,
        v2_code = Code.OUT_OF_RANGE,
        v2_message = s"PARTICIPANT_PRUNED_DATA_ACCESSED(12,$correlationId): my message",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("PARTICIPANT_PRUNED_DATA_ACCESSED"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return an offsetAfterLedgerEnd error" in {
      assertVersionedError(_.offsetAfterLedgerEnd("my message"))(
        v1_code = Code.OUT_OF_RANGE,
        v1_message = "my message",
        v1_details = Seq.empty,
        v2_code = Code.OUT_OF_RANGE,
        v2_message = s"REQUESTED_OFFSET_OUT_OF_RANGE(12,$correlationId): my message",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("REQUESTED_OFFSET_OUT_OF_RANGE"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return a serviceNotRunning error" in {
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        assertVersionedError(_.serviceNotRunning(definiteAnswer))(
          v1_code = Code.UNAVAILABLE,
          v1_message = "Service has been shut down.",
          v1_details = expectedDetails,
          v2_code = Code.UNAVAILABLE,
          v2_message = s"SERVICE_NOT_RUNNING(1,$correlationId): Service has been shut down.",
          v2_details = Seq[ErrorDetails.ErrorDetail](
            ErrorDetails.ErrorInfoDetail("SERVICE_NOT_RUNNING"),
            DefaultTraceIdRequestInfo,
            ErrorDetails.RetryInfoDetail(1),
          ),
        )
      }
    }

    "return a missingLedgerConfigUponRequest error" in {
      assertVersionedError(_.missingLedgerConfigUponRequest)(
        v1_code = Code.NOT_FOUND,
        v1_message = "The ledger configuration is not available.",
        v1_details = Seq.empty,
        v2_code = Code.NOT_FOUND,
        v2_message =
          s"LEDGER_CONFIGURATION_NOT_FOUND(11,$correlationId): The ledger configuration is not available.",
        v2_details = Seq[ErrorDetails.ErrorDetail](
          ErrorDetails.ErrorInfoDetail("LEDGER_CONFIGURATION_NOT_FOUND"),
          DefaultTraceIdRequestInfo,
        ),
      )
    }

    "return a missingField error" in {
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        assertVersionedError(_.missingField("my field", definiteAnswer))(
          v1_code = Code.INVALID_ARGUMENT,
          v1_message = "Missing field: my field",
          v1_details = expectedDetails,
          v2_code = Code.INVALID_ARGUMENT,
          v2_message =
            s"MISSING_FIELD(8,$correlationId): The submitted command is missing a mandatory field: my field",
          v2_details = Seq[ErrorDetails.ErrorDetail](
            ErrorDetails.ErrorInfoDetail("MISSING_FIELD"),
            DefaultTraceIdRequestInfo,
          ),
        )
      }
    }

    "return an invalidArgument error" in {
      val testCases = Table(
        ("definite answer", "expected details"),
        (None, Seq.empty),
        (Some(false), Seq(definiteAnswers(false))),
      )

      forEvery(testCases) { (definiteAnswer, expectedDetails) =>
        assertVersionedError(_.invalidArgument(definiteAnswer)("my message"))(
          v1_code = Code.INVALID_ARGUMENT,
          v1_message = "Invalid argument: my message",
          v1_details = expectedDetails,
          v2_code = Code.INVALID_ARGUMENT,
          v2_message =
            s"INVALID_ARGUMENT(8,$correlationId): The submitted command has invalid arguments: my message",
          v2_details = Seq[ErrorDetails.ErrorDetail](
            ErrorDetails.ErrorInfoDetail("INVALID_ARGUMENT"),
            DefaultTraceIdRequestInfo,
          ),
        )
      }
    }

    "should create an ApiException without the stack trace" in {
      val status = Status.newBuilder().setCode(Code.INTERNAL.value()).build()
      val exception = grpcError(status)
      exception.getStackTrace shouldBe Array.empty
    }
  }

  private def assertVersionedError(
      error: ErrorFactories => StatusRuntimeException
  )(
      v1_code: Code,
      v1_message: String,
      v1_details: Seq[Any],
      v2_code: Code,
      v2_message: String,
      v2_details: Seq[ErrorDetails.ErrorDetail],
  ): Unit = {
    val errorFactoriesV1 = ErrorFactories(new ErrorCodesVersionSwitcher(false))
    val errorFactoriesV2 = ErrorFactories(new ErrorCodesVersionSwitcher(true))
    assertV1Error(error(errorFactoriesV1))(v1_code, v1_message, v1_details)
    assertV2Error(error(errorFactoriesV2))(v2_code, v2_message, v2_details)
  }

  private def assertV1Error(
      statusRuntimeException: StatusRuntimeException
  )(expectedCode: Code, expectedMessage: String, expectedDetails: Seq[Any]): Unit = {
    val status = StatusProto.fromThrowable(statusRuntimeException)
    status.getCode shouldBe expectedCode.value()
    status.getMessage shouldBe expectedMessage
    val _ = status.getDetailsList.asScala shouldBe expectedDetails
  }

  private def assertV2Error(
      statusRuntimeException: StatusRuntimeException
  )(
      expectedCode: Code,
      expectedMessage: String,
      expectedDetails: Seq[ErrorDetails.ErrorDetail],
  ): Unit = {
    val status = StatusProto.fromThrowable(statusRuntimeException)
    status.getCode shouldBe expectedCode.value()
    status.getMessage shouldBe expectedMessage
    val details = status.getDetailsList.asScala.toSeq
    val _ = ErrorDetails.from(details) should contain theSameElementsAs (expectedDetails)
    // TODO error codes: Assert logging
  }
}

object ErrorDetails {

  sealed trait ErrorDetail

  final case class ResourceInfoDetail(name: String, typ: String) extends ErrorDetail

  final case class ErrorInfoDetail(reason: String) extends ErrorDetail

  final case class RetryInfoDetail(retryDelayInSeconds: Long) extends ErrorDetail

  final case class RequestInfoDetail(requestId: String) extends ErrorDetail

  def from(anys: Seq[protobuf.Any]): Seq[ErrorDetail] = {
    anys.toList.map(from)
  }

  private def from(any: protobuf.Any): ErrorDetail = {
    if (any.is(classOf[ResourceInfo])) {
      val v = any.unpack(classOf[ResourceInfo])
      ResourceInfoDetail(v.getResourceType, v.getResourceName)
    } else if (any.is(classOf[ErrorInfo])) {
      val v = any.unpack(classOf[ErrorInfo])
      ErrorInfoDetail(v.getReason)
    } else if (any.is(classOf[RetryInfo])) {
      val v = any.unpack(classOf[RetryInfo])
      RetryInfoDetail(v.getRetryDelay.getSeconds)
    } else if (any.is(classOf[RequestInfo])) {
      val v = any.unpack(classOf[RequestInfo])
      RequestInfoDetail(v.getRequestId)
    } else {
      throw new IllegalStateException(s"Could not unpack value of: |$any|")
    }
  }
}
