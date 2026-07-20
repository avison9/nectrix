package com.nectrix.coreapp.trading.api;

import com.nectrix.coreapp.trading.api.CopyRelationshipArchiveExport.CopiedTradeRecord;
import com.nectrix.coreapp.trading.api.CopyRelationshipArchiveExport.CopyRelationshipRecord;
import com.nectrix.coreapp.trading.api.CopyRelationshipArchiveExport.TradeSignalRecord;
import com.nectrix.coreapp.trading.domain.CopiedTrade;
import com.nectrix.coreapp.trading.domain.CopyRelationship;
import com.nectrix.coreapp.trading.domain.TradeSignal;
import com.nectrix.coreapp.trading.repository.CopiedTradeRepository;
import com.nectrix.coreapp.trading.repository.CopyRelationshipRepository;
import com.nectrix.coreapp.trading.repository.TradeSignalRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CopyRelationshipArchivalApiImpl implements CopyRelationshipArchivalApi {

  private final CopyRelationshipRepository copyRelationshipRepository;
  private final CopiedTradeRepository copiedTradeRepository;
  private final TradeSignalRepository tradeSignalRepository;

  public CopyRelationshipArchivalApiImpl(
      CopyRelationshipRepository copyRelationshipRepository,
      CopiedTradeRepository copiedTradeRepository,
      TradeSignalRepository tradeSignalRepository) {
    this.copyRelationshipRepository = copyRelationshipRepository;
    this.copiedTradeRepository = copiedTradeRepository;
    this.tradeSignalRepository = tradeSignalRepository;
  }

  @Override
  public CopyRelationshipArchiveExport findForExport(UUID brokerAccountId) {
    List<CopyRelationship> relationships =
        copyRelationshipRepository.findAllByBrokerAccountId(brokerAccountId);
    List<UUID> relationshipIds = relationships.stream().map(CopyRelationship::id).toList();
    List<CopiedTrade> trades = copiedTradeRepository.findAllForRelationshipIds(relationshipIds);
    List<TradeSignal> signals =
        tradeSignalRepository.findAllByMasterBrokerAccountId(brokerAccountId);
    return new CopyRelationshipArchiveExport(
        relationships.stream().map(this::toRecord).toList(),
        trades.stream().map(this::toRecord).toList(),
        signals.stream().map(this::toRecord).toList());
  }

  @Override
  public void deleteForBrokerAccount(UUID brokerAccountId) {
    List<CopyRelationship> relationships =
        copyRelationshipRepository.findAllByBrokerAccountId(brokerAccountId);
    List<UUID> relationshipIds = relationships.stream().map(CopyRelationship::id).toList();
    // Order matters — see this interface's own Javadoc: copied_trades before trade_signals
    // (copied_trades.trade_signal_id has no cascade), both before copy_relationships.
    copiedTradeRepository.deleteForRelationshipIds(relationshipIds);
    tradeSignalRepository.deleteByMasterBrokerAccountId(brokerAccountId);
    copyRelationshipRepository.deleteByIds(relationshipIds);
  }

  private CopyRelationshipRecord toRecord(CopyRelationship r) {
    return new CopyRelationshipRecord(
        r.id(),
        r.masterProfileId(),
        r.masterBrokerAccountId(),
        r.followerUserId(),
        r.followerBrokerAccountId(),
        r.moneyManagementProfileId(),
        r.riskProfileId(),
        r.status(),
        r.copyDirection(),
        r.performanceFeePercent(),
        r.feeCollectionMethod(),
        r.highWaterMark(),
        r.riskAckAt(),
        r.originatingInvitationId(),
        r.originatingFollowRequestId(),
        r.createdAt(),
        r.stoppedAt());
  }

  private CopiedTradeRecord toRecord(CopiedTrade t) {
    return new CopiedTradeRecord(
        t.id(),
        t.copyRelationshipId(),
        t.tradeSignalId(),
        t.status(),
        t.canonicalSymbol(),
        t.computedVolumeLots(),
        t.requestedPrice(),
        t.filledPrice(),
        t.slippagePips(),
        t.rejectReason(),
        t.realizedPnl(),
        t.openedAt(),
        t.closedAt(),
        t.createdAt());
  }

  private TradeSignalRecord toRecord(TradeSignal s) {
    return new TradeSignalRecord(
        s.id(),
        s.masterBrokerAccountId(),
        s.brokerPositionId(),
        s.eventType(),
        s.canonicalSymbol(),
        s.direction(),
        s.volumeLots(),
        s.closedVolumeLots(),
        s.fillPrice(),
        s.slPrice(),
        s.tpPrice(),
        s.serverTimestamp(),
        s.receivedAtGateway(),
        s.rawPayload());
  }
}
