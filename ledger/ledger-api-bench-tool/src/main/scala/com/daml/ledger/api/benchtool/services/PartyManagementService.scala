// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.benchtool.services

import com.daml.ledger.api.v1.admin.party_management_service.{
  AllocatePartyRequest,
  PartyManagementServiceGrpc,
}
import com.daml.ledger.client.binding.Primitive.Party
import io.grpc.Channel
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

class PartyManagementService(channel: Channel) {
  private val logger: Logger = LoggerFactory.getLogger(getClass)
  private val service: PartyManagementServiceGrpc.PartyManagementServiceStub =
    PartyManagementServiceGrpc.stub(channel)

  def allocateParty(hint: String)(implicit ec: ExecutionContext): Future[Party] = {
    logger.info(s"Allocating party with hint: $hint")
    service
      .allocateParty(new AllocatePartyRequest(partyIdHint = hint))
      .map(r => Party(r.partyDetails.get.party))
  }

}
