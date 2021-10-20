// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool.services

import com.daml.ledger.api.v1.ledger_identity_service.{
  GetLedgerIdentityRequest,
  LedgerIdentityServiceGrpc,
}
import io.grpc.Channel
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

final class LedgerIdentityService(channel: Channel) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val service = LedgerIdentityServiceGrpc.stub(channel)

  def fetchLedgerId()(implicit ec: ExecutionContext): Future[String] = {
    service
      .getLedgerIdentity(
        new GetLedgerIdentityRequest()
      )
      .map { response =>
        logger.info(s"Fetched ledger ID: ${response.ledgerId}")
        response.ledgerId
      }
  }

}
