package ctraderapi

import (
	"context"

	openapi "github.com/avison9/nectrix/ctrader-proto/go/gen"
	"google.golang.org/protobuf/proto"
)

// ApplicationAuth must succeed before any other request on a freshly-dialed
// connection — "If you send any messages before your application is
// authorised, you will receive an error" (help.ctrader.com/open-api/connection).
func (c *Client) ApplicationAuth(ctx context.Context, clientID, clientSecret string) error {
	req := &openapi.ProtoOAApplicationAuthReq{
		ClientId:     proto.String(clientID),
		ClientSecret: proto.String(clientSecret),
	}
	resp := &openapi.ProtoOAApplicationAuthRes{}
	return c.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_APPLICATION_AUTH_REQ), req, resp)
}

// AccountAuth authorizes one trading account on an already app-authed
// connection — required before any account-scoped call (Trader, Reconcile,
// NewOrder, ...).
func (c *Client) AccountAuth(ctx context.Context, ctidTraderAccountID int64, accessToken string) error {
	req := &openapi.ProtoOAAccountAuthReq{
		CtidTraderAccountId: proto.Int64(ctidTraderAccountID),
		AccessToken:         proto.String(accessToken),
	}
	resp := &openapi.ProtoOAAccountAuthRes{}
	return c.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_ACCOUNT_AUTH_REQ), req, resp)
}

// GetAccountListByAccessToken lists every cTrader trading account reachable
// by accessToken — used once during the OAuth linking flow (before any
// broker_accounts row exists yet) to let the user pick which account(s) to
// link. Requires ApplicationAuth first, but no AccountAuth (this call isn't
// scoped to one account).
func (c *Client) GetAccountListByAccessToken(ctx context.Context, accessToken string) ([]*openapi.ProtoOACtidTraderAccount, error) {
	req := &openapi.ProtoOAGetAccountListByAccessTokenReq{
		AccessToken: proto.String(accessToken),
	}
	resp := &openapi.ProtoOAGetAccountListByAccessTokenRes{}
	if err := c.Request(ctx, uint32(openapi.ProtoOAPayloadType_PROTO_OA_GET_ACCOUNTS_BY_ACCESS_TOKEN_REQ), req, resp); err != nil {
		return nil, err
	}
	return resp.GetCtidTraderAccount(), nil
}
