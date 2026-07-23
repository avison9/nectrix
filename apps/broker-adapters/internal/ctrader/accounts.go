package ctrader

import (
	"context"
	"fmt"
	"sort"
)

// AccountSummary is one trading account associated with an OAuth access
// token — the result of ProtoOAGetAccountListByAccessTokenReq. Used only
// during the OAuth linking flow, before any broker_accounts row (and thus
// any domain.ConnectionHandle) exists yet, so it deliberately doesn't route
// through Connect/lookup like every other method in this package.
type AccountSummary struct {
	CtidTraderAccountID int64
	IsLive              bool
	TraderLogin         int64
	BrokerTitleShort    string
}

// ListAccountsByAccessToken lists every trading account granted to
// accessToken, across BOTH the demo and live environments. cTrader's own
// proto doc-comment for ProtoOACtidTraderAccount.isLive ("If TRUE... live
// host must be used to authorize it") only documents the per-account
// AUTHORIZATION step, not which host the LISTING call itself must run
// against — querying both hosts and merging by ctidTraderAccountId is the
// safe interpretation, to be confirmed for real during this ticket's
// live-verification pass. A host that errors (e.g. this token genuinely has
// no live accounts) is logged and skipped, not fatal, as long as the other
// host returns at least one account.
func (a *CTraderAdapter) ListAccountsByAccessToken(ctx context.Context, accessToken string) ([]AccountSummary, error) {
	byID := make(map[int64]AccountSummary)
	var lastErr error
	for _, host := range []string{a.demoHost, a.liveHost} {
		accounts, err := a.listAccountsAgainstHost(ctx, host, accessToken)
		if err != nil {
			a.logger.Warn("ctrader: list accounts by access token failed against a host", "host", host, "error", err)
			lastErr = err
			continue
		}
		for _, acc := range accounts {
			byID[acc.CtidTraderAccountID] = acc
		}
	}
	if len(byID) == 0 {
		return nil, fmt.Errorf("ctrader: no accounts found for access token (checked both demo and live hosts): %w", lastErr)
	}

	result := make([]AccountSummary, 0, len(byID))
	for _, acc := range byID {
		result = append(result, acc)
	}
	// Bugfix — Go map iteration order is randomized, so without this the account-picking list
	// (apps/web's CtraderCallbackClient) rendered in a different, unpredictable order every time
	// a user re-ran this flow to link a second/third account under the same OAuth grant. Sorted
	// by CtidTraderAccountID so the list is stable across repeated visits.
	sort.Slice(result, func(i, j int) bool { return result[i].CtidTraderAccountID < result[j].CtidTraderAccountID })
	return result, nil
}

func (a *CTraderAdapter) listAccountsAgainstHost(ctx context.Context, host, accessToken string) ([]AccountSummary, error) {
	client, err := a.dial(ctx, host, a.logger)
	if err != nil {
		return nil, fmt.Errorf("ctrader: dial %s: %w", host, err)
	}
	defer func() { _ = client.Close() }()

	if err := client.ApplicationAuth(ctx, a.appClientID, a.appClientSecret); err != nil {
		return nil, fmt.Errorf("ctrader: application auth against %s: %w", host, err)
	}

	accounts, err := client.GetAccountListByAccessToken(ctx, accessToken)
	if err != nil {
		return nil, fmt.Errorf("ctrader: get account list against %s: %w", host, err)
	}

	result := make([]AccountSummary, 0, len(accounts))
	for _, acc := range accounts {
		result = append(result, AccountSummary{
			CtidTraderAccountID: int64(acc.GetCtidTraderAccountId()),
			IsLive:              acc.GetIsLive(),
			TraderLogin:         acc.GetTraderLogin(),
			BrokerTitleShort:    acc.GetBrokerTitleShort(),
		})
	}
	return result, nil
}
