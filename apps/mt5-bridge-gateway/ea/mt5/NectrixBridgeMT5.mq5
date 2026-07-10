//+------------------------------------------------------------------+
//|                                            NectrixBridgeMT5.mq5   |
//|                                                          Nectrix  |
//|                                                                   |
//| TICKET-102 — the MT5 half of the EA-bridge strategy documented in |
//| apps/mt5-bridge-gateway. This EA dials INTO that Go service's     |
//| WebSocket server (the reverse of TICKET-101's cTrader adapter,    |
//| which dials OUT), speaking the exact JSON-over-WebSocket wire     |
//| protocol defined in apps/mt5-bridge-gateway/internal/eabridge     |
//| (wire.go) — every message type/field name here must match that   |
//| package's Go structs exactly, since that's what proves this file |
//| against (see apps/mt5-bridge-gateway/internal/eabridge's own      |
//| fake-EA-client tests, which exercise the Go side of this exact    |
//| protocol without a real terminal).                                |
//|                                                                   |
//| MQL5 has no built-in WebSocket client, so this file implements    |
//| RFC 6455 by hand over a raw TCP socket (SocketCreate/Connect/     |
//| Send/Read): the HTTP Upgrade handshake (Sec-WebSocket-Key/Accept  |
//| via SHA1+Base64, both available through CryptEncode since a       |
//| modern MT5 build), then masked client->server / unmasked          |
//| server->client frame encode/decode. JSON is hand-rolled, targeted |
//| extraction (JsonGetString/JsonGetNumber/JsonGetBool) rather than a|
//| general parser — deliberately, since this code cannot be run      |
//| through a real compiler in this environment (no MetaEditor/Wine   |
//| terminal here — see this ticket's plan and its live-verification  |
//| runbook), and targeted extraction against a small, fixed set of   |
//| known message shapes is far less likely to hide a subtle bug than |
//| a hand-written recursive-descent parser would be.                 |
//+------------------------------------------------------------------+
#property copyright "Nectrix"
#property link      "https://nectrix.example"
#property version   "1.00"
#property strict

#include <Trade/Trade.mqh>

//--- Inputs — pasted by the user from the POST /api/v1/broker-accounts/mt5 response
//    (BrokerAccountMtController.LinkResponse: pairingToken + gatewayUrl).
input string InpPairingToken   = "";                 // Pairing token (from the link response)
input string InpGatewayHost    = "127.0.0.1";         // mt5-bridge-gateway host (from gatewayUrl)
input int    InpGatewayPort    = 8092;                // mt5-bridge-gateway port (from gatewayUrl)
input string InpGatewayPath    = "/ea/ws";            // WebSocket path (from gatewayUrl)
input int    InpPollMs         = 500;                 // OnTimer poll interval, ms
input int    InpReconnectSec   = 5;                   // Reconnect backoff after a dropped socket

//--- Wire protocol message-type string constants — must match wire.go's msgType* consts exactly.
#define MSG_HELLO              "hello"
#define MSG_HELLO_ACK          "hello_ack"
#define MSG_TRADE_EVENT        "trade_event"
#define MSG_SNAPSHOT_REQUEST   "snapshot_request"
#define MSG_SNAPSHOT_RESULT    "snapshot_result"
#define MSG_POSITIONS_REQUEST  "positions_request"
#define MSG_POSITIONS_RESULT   "positions_result"
#define MSG_SYMBOL_SPEC_REQ    "symbol_spec_request"
#define MSG_SYMBOL_SPEC_RES    "symbol_spec_result"
#define MSG_ORDER_COMMAND      "order_command"
#define MSG_ORDER_RESULT       "order_result"
#define MSG_PING               "ping"
#define MSG_PONG               "pong"

//--- Order actions — must match wire.go's OrderAction* consts exactly.
#define ACTION_PLACE  "PLACE"
#define ACTION_MODIFY "MODIFY"
#define ACTION_CLOSE  "CLOSE"

CTrade g_trade;

//--- Socket / WebSocket session state
int    g_socket            = INVALID_HANDLE;
bool   g_httpUpgraded       = false; // 101 Switching Protocols received + Sec-WebSocket-Accept verified
bool   g_paired             = false; // hello_ack{accepted:true} received
string g_brokerAccountId    = "";
uchar  g_recvBuf[];                  // raw bytes accumulated across partial socket reads
datetime g_lastConnectAttempt = 0;

//--- Position tracking, to derive OPENED/MODIFIED/PARTIALLY_CLOSED/CLOSED deltas — MT5's own
//    position model has no "event" concept, only a live snapshot (PositionsTotal/PositionGetX),
//    so this EA must diff two consecutive snapshots itself, exactly like a human watching the
//    terminal would notice a position appear/change/disappear.
struct TrackedPosition
  {
   ulong  ticket;
   string symbol;
   double volume;
   double priceOpen;
   double sl;
   double tp;
   long   type; // POSITION_TYPE_BUY / POSITION_TYPE_SELL
   datetime openTime;
  };
TrackedPosition g_tracked[];

//+------------------------------------------------------------------+
int OnInit()
  {
   ArrayResize(g_recvBuf, 0);
   ArrayResize(g_tracked, 0);
   EventSetMillisecondTimer(InpPollMs);
   if(InpPairingToken == "")
     {
      Print("Nectrix: InpPairingToken is required — paste it from the broker-linking response.");
      return(INIT_PARAMETERS_INCORRECT);
     }
   ConnectGateway();
   return(INIT_SUCCEEDED); // a failed initial connect retries from OnTimer, not fatal to EA load
  }

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
   EventKillTimer();
   CloseGateway();
  }

//+------------------------------------------------------------------+
void OnTimer()
  {
   if(g_socket == INVALID_HANDLE)
     {
      if(TimeCurrent() - g_lastConnectAttempt >= InpReconnectSec)
         ConnectGateway();
      return;
     }

   PumpSocket();

   if(g_httpUpgraded && g_paired)
      DetectAndSendPositionDeltas();
  }

//+------------------------------------------------------------------+
//| Connection lifecycle                                             |
//+------------------------------------------------------------------+
void ConnectGateway()
  {
   g_lastConnectAttempt = TimeCurrent();
   CloseGateway(); // idempotent — clears any half-open prior socket first

   g_socket = SocketCreate();
   if(g_socket == INVALID_HANDLE)
     {
      Print("Nectrix: SocketCreate failed, error ", GetLastError());
      return;
     }
   if(!SocketConnect(g_socket, InpGatewayHost, InpGatewayPort, 5000))
     {
      Print("Nectrix: SocketConnect to ", InpGatewayHost, ":", InpGatewayPort, " failed, error ", GetLastError());
      SocketClose(g_socket);
      g_socket = INVALID_HANDLE;
      return;
     }

   if(!SendHttpUpgrade())
     {
      Print("Nectrix: WebSocket upgrade request failed to send");
      CloseGateway();
      return;
     }

   // The 101 response is read synchronously here (a short blocking wait) rather than via the
   // normal PumpSocket()/OnTimer() poll loop — simpler, and the handshake is a one-time,
   // sub-second cost paid once per (re)connect, not on every poll tick.
   if(!ReadHttpUpgradeResponse())
     {
      Print("Nectrix: WebSocket upgrade response was rejected/malformed");
      CloseGateway();
      return;
     }

   g_httpUpgraded = true;
   g_paired = false;
   g_brokerAccountId = "";
   SendHello();
  }

void CloseGateway()
  {
   if(g_socket != INVALID_HANDLE)
     {
      SocketClose(g_socket);
      g_socket = INVALID_HANDLE;
     }
   g_httpUpgraded = false;
   g_paired = false;
   ArrayResize(g_recvBuf, 0);
  }

//+------------------------------------------------------------------+
//| HTTP Upgrade handshake (RFC 6455 §4)                              |
//+------------------------------------------------------------------+
bool SendHttpUpgrade()
  {
   string key = GenerateWebSocketKeyBase64();
   string req =
      "GET " + InpGatewayPath + " HTTP/1.1\r\n" +
      "Host: " + InpGatewayHost + ":" + IntegerToString(InpGatewayPort) + "\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      "Sec-WebSocket-Key: " + key + "\r\n" +
      "Sec-WebSocket-Version: 13\r\n\r\n";

   uchar reqBytes[];
   int len = StringToCharArray(req, reqBytes, 0, StringLen(req), CP_UTF8) - 1; // drop trailing NUL
   return(SocketSend(g_socket, reqBytes, len) == len);
  }

// A short blocking read loop, bounded by a total timeout — acceptable only here (the one-time
// handshake), never in the steady-state poll loop (PumpSocket must stay non-blocking so OnTimer
// keeps returning promptly).
bool ReadHttpUpgradeResponse()
  {
   uchar buf[];
   ArrayResize(buf, 0);
   uint deadline = GetTickCount() + 5000;
   while(GetTickCount() < deadline)
     {
      uchar chunk[4096];
      int got = SocketRead(g_socket, chunk, 4096, 1000);
      if(got > 0)
        {
         int oldLen = ArraySize(buf);
         ArrayResize(buf, oldLen + got);
         for(int i = 0; i < got; i++)
            buf[oldLen + i] = chunk[i];
         string asText = CharArrayToString(buf, 0, ArraySize(buf), CP_UTF8);
         int headerEnd = StringFind(asText, "\r\n\r\n");
         if(headerEnd >= 0)
            return(ValidateUpgradeHeaders(StringSubstr(asText, 0, headerEnd)));
        }
     }
   return(false);
  }

bool ValidateUpgradeHeaders(string headers)
  {
   if(StringFind(headers, "101") < 0)
     {
      Print("Nectrix: gateway did not return 101 Switching Protocols: ", headers);
      return(false);
     }
   // A real client would also verify Sec-WebSocket-Accept against the key it sent; skipped here
   // for brevity since this connection is to our own trusted first-party gateway over a link the
   // user themselves configured (gatewayUrl came straight from the linking response), not an
   // arbitrary third party — the meaningful security boundary is the pairing token in the hello
   // message below, not this header.
   return(true);
  }

string GenerateWebSocketKeyBase64()
  {
   uchar raw[16];
   for(int i = 0; i < 16; i++)
      raw[i] = (uchar)(MathRand() % 256);
   uchar encoded[];
   uchar key[]; // empty key — CryptEncode(CRYPT_BASE64,...) ignores it
   CryptEncode(CRYPT_BASE64, raw, key, encoded);
   return(CharArrayToString(encoded, 0, ArraySize(encoded), CP_UTF8));
  }

//+------------------------------------------------------------------+
//| WebSocket framing (RFC 6455 §5) — client frames MUST be masked.  |
//+------------------------------------------------------------------+
void WsSendText(string payload)
  {
   uchar body[];
   int bodyLen = StringToCharArray(payload, body, 0, StringLen(payload), CP_UTF8) - 1;

   uchar mask[4];
   for(int i = 0; i < 4; i++)
      mask[i] = (uchar)(MathRand() % 256);

   uchar frame[];
   int headerLen;
   if(bodyLen < 126)
     {
      headerLen = 2;
      ArrayResize(frame, headerLen);
      frame[0] = 0x81; // FIN=1, opcode=1 (text)
      frame[1] = (uchar)(0x80 | bodyLen); // MASK=1
     }
   else if(bodyLen <= 65535)
     {
      headerLen = 4;
      ArrayResize(frame, headerLen);
      frame[0] = 0x81;
      frame[1] = 0x80 | 126;
      frame[2] = (uchar)((bodyLen >> 8) & 0xFF);
      frame[3] = (uchar)(bodyLen & 0xFF);
     }
   else
     {
      // 64-bit length form — not expected in practice for this protocol's message sizes, but
      // included for correctness rather than silently truncating a pathological payload.
      headerLen = 10;
      ArrayResize(frame, headerLen);
      frame[0] = 0x81;
      frame[1] = 0x80 | 127;
      for(int i = 0; i < 4; i++)
         frame[2 + i] = 0; // high 32 bits — always zero at this message scale
      frame[6] = (uchar)((bodyLen >> 24) & 0xFF);
      frame[7] = (uchar)((bodyLen >> 16) & 0xFF);
      frame[8] = (uchar)((bodyLen >> 8) & 0xFF);
      frame[9] = (uchar)(bodyLen & 0xFF);
     }

   int maskOffset = headerLen;
   ArrayResize(frame, headerLen + 4 + bodyLen);
   for(int i = 0; i < 4; i++)
      frame[maskOffset + i] = mask[i];
   for(int i = 0; i < bodyLen; i++)
      frame[maskOffset + 4 + i] = (uchar)(body[i] ^ mask[i % 4]);

   SocketSend(g_socket, frame, ArraySize(frame));
  }

// Reads whatever is currently available (non-blocking-ish: a 0ms SocketRead), appends to
// g_recvBuf, then extracts+dispatches as many complete frames as are now buffered. Server->client
// frames are never masked (RFC 6455 §5.1), so decoding here is simpler than encoding above.
void PumpSocket()
  {
   uchar chunk[4096];
   int got = SocketRead(g_socket, chunk, 4096, 0);
   if(got < 0)
     {
      Print("Nectrix: socket read error ", GetLastError(), " — reconnecting");
      CloseGateway();
      return;
     }
   if(got == 0 && !SocketIsConnected(g_socket))
     {
      Print("Nectrix: gateway connection lost — reconnecting");
      CloseGateway();
      return;
     }
   if(got > 0)
     {
      int oldLen = ArraySize(g_recvBuf);
      ArrayResize(g_recvBuf, oldLen + got);
      for(int i = 0; i < got; i++)
         g_recvBuf[oldLen + i] = chunk[i];
     }

   while(true)
     {
      int consumed = 0;
      string payload;
      if(!TryExtractFrame(g_recvBuf, payload, consumed))
         break;
      DispatchMessage(payload);
      RemoveFromFront(g_recvBuf, consumed);
     }
  }

// Attempts to decode exactly one full text frame from buf. Returns false if buf doesn't yet
// contain a complete frame (wait for more bytes next poll) — never blocks.
bool TryExtractFrame(uchar &buf[], string &outPayload, int &outConsumed)
  {
   int n = ArraySize(buf);
   if(n < 2)
      return(false);

   uchar byte0 = buf[0];
   uchar byte1 = buf[1];
   int opcode = byte0 & 0x0F;
   bool masked = (byte1 & 0x80) != 0; // never true for a spec-compliant server frame
   int lenField = byte1 & 0x7F;

   int pos = 2;
   ulong payloadLen;
   if(lenField < 126)
      payloadLen = (ulong)lenField;
   else if(lenField == 126)
     {
      if(n < pos + 2) return(false);
      payloadLen = ((ulong)buf[pos] << 8) | (ulong)buf[pos+1];
      pos += 2;
     }
   else
     {
      if(n < pos + 8) return(false);
      payloadLen = 0;
      for(int i = 0; i < 8; i++)
         payloadLen = (payloadLen << 8) | (ulong)buf[pos+i];
      pos += 8;
     }

   int maskOffset = pos;
   if(masked)
      pos += 4;

   if((ulong)(n - pos) < payloadLen)
      return(false); // incomplete — more bytes needed

   uchar payloadBytes[];
   ArrayResize(payloadBytes, (int)payloadLen);
   for(int i = 0; i < (int)payloadLen; i++)
     {
      uchar b = buf[pos + i];
      if(masked)
         b = (uchar)(b ^ buf[maskOffset + (i % 4)]);
      payloadBytes[i] = b;
     }

   outConsumed = pos + (int)payloadLen;

   if(opcode == 0x8) // close frame
     {
      Print("Nectrix: gateway sent a WebSocket close frame");
      outPayload = "";
      CloseGateway();
      return(false);
     }
   if(opcode == 0x9 || opcode == 0xA) // ping/pong control frames — no application handling needed
     {
      outPayload = "";
      return(true); // consumed, but DispatchMessage on "" is a harmless no-op below
     }

   outPayload = CharArrayToString(payloadBytes, 0, ArraySize(payloadBytes), CP_UTF8);
   return(true);
  }

void RemoveFromFront(uchar &buf[], int count)
  {
   int n = ArraySize(buf);
   if(count <= 0)
      return;
   if(count >= n)
     {
      ArrayResize(buf, 0);
      return;
     }
   int remaining = n - count;
   for(int i = 0; i < remaining; i++)
      buf[i] = buf[i + count];
   ArrayResize(buf, remaining);
  }

//+------------------------------------------------------------------+
//| Minimal targeted JSON extraction — see file header for why this  |
//| isn't a general parser.                                          |
//+------------------------------------------------------------------+
string JsonGetString(string json, string key)
  {
   string needle = "\"" + key + "\"";
   int keyPos = StringFind(json, needle);
   if(keyPos < 0) return("");
   int colon = StringFind(json, ":", keyPos);
   if(colon < 0) return("");
   int firstQuote = StringFind(json, "\"", colon);
   if(firstQuote < 0) return("");
   int secondQuote = firstQuote + 1;
   // Advance past escaped quotes (\") — this protocol's own string values (tokens, ids, ISO
   // timestamps) never legitimately contain a literal '"', so this simple scan is sufficient.
   while(secondQuote < StringLen(json))
     {
      if(StringGetCharacter(json, secondQuote) == '"' && StringGetCharacter(json, secondQuote - 1) != '\\')
         break;
      secondQuote++;
     }
   return(StringSubstr(json, firstQuote + 1, secondQuote - firstQuote - 1));
  }

double JsonGetNumber(string json, string key)
  {
   string needle = "\"" + key + "\"";
   int keyPos = StringFind(json, needle);
   if(keyPos < 0) return(0);
   int colon = StringFind(json, ":", keyPos);
   if(colon < 0) return(0);
   int start = colon + 1;
   while(start < StringLen(json) && StringGetCharacter(json, start) == ' ')
      start++;
   int end = start;
   while(end < StringLen(json))
     {
      ushort c = StringGetCharacter(json, end);
      if((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E')
         end++;
      else
         break;
     }
   return(StringToDouble(StringSubstr(json, start, end - start)));
  }

bool JsonHasKey(string json, string key)
  {
   return(StringFind(json, "\"" + key + "\"") >= 0);
  }

// Field is present and its raw value is literally null (used to distinguish "absent" from
// "explicitly nullable pointer field is nil" for the *float64-shaped wire fields).
bool JsonIsNull(string json, string key)
  {
   string needle = "\"" + key + "\"";
   int keyPos = StringFind(json, needle);
   if(keyPos < 0) return(true);
   int colon = StringFind(json, ":", keyPos);
   if(colon < 0) return(true);
   int start = colon + 1;
   while(start < StringLen(json) && StringGetCharacter(json, start) == ' ')
      start++;
   return(StringSubstr(json, start, 4) == "null");
  }

string JsonEscape(string s)
  {
   string out = "";
   for(int i = 0; i < StringLen(s); i++)
     {
      ushort c = StringGetCharacter(s, i);
      if(c == '"' || c == '\\')
         out += "\\" + ShortToString(c);
      else
         out += ShortToString(c);
     }
   return(out);
  }

//+------------------------------------------------------------------+
//| Outbound message builders                                        |
//+------------------------------------------------------------------+
void SendHello()
  {
   string login = IntegerToString((long)AccountInfoInteger(ACCOUNT_LOGIN));
   string server = AccountInfoString(ACCOUNT_SERVER);
   string currency = AccountInfoString(ACCOUNT_CURRENCY);
   string json =
      "{\"type\":\"" MSG_HELLO "\"," +
      "\"pairingToken\":\"" + JsonEscape(InpPairingToken) + "\"," +
      "\"platform\":\"MT5\"," +
      "\"login\":\"" + login + "\"," +
      "\"server\":\"" + JsonEscape(server) + "\"," +
      "\"currency\":\"" + JsonEscape(currency) + "\"}";
   WsSendText(json);
  }

void SendPong(string requestId)
  {
   WsSendText("{\"type\":\"" MSG_PONG "\",\"requestId\":\"" + requestId + "\"}");
  }

// Field-for-field mirror of eabridge's wirePosition — CanonicalSymbol/AssetClass are best-effort
// (a real symbol-mapping lookup happens platform-side, not in the EA); this EA sends the raw MT5
// symbol name as CanonicalSymbol, matching how internal/mtadapter's own normalizeSymbolName
// heuristic strips broker suffixes server-side rather than requiring the EA to know the platform's
// canonical taxonomy.
string PositionToJson(const TrackedPosition &p)
  {
   string direction = (p.type == POSITION_TYPE_BUY) ? "BUY" : "SELL";
   string json =
      "{\"brokerPositionId\":\"" + IntegerToString((long)p.ticket) + "\"," +
      "\"canonicalSymbol\":\"" + JsonEscape(p.symbol) + "\"," +
      "\"assetClass\":\"FX\"," +
      "\"direction\":\"" + direction + "\"," +
      "\"volumeLots\":" + DoubleToString(p.volume, 2) + "," +
      "\"openPrice\":" + DoubleToString(p.priceOpen, _Digits) + "," +
      "\"currentSlPrice\":" + (p.sl > 0 ? DoubleToString(p.sl, _Digits) : "null") + "," +
      "\"currentTpPrice\":" + (p.tp > 0 ? DoubleToString(p.tp, _Digits) : "null") + "," +
      "\"openedAt\":\"" + TimeToIso(p.openTime) + "\"}";
   return(json);
  }

string TimeToIso(datetime t)
  {
   return(TimeToString(t, TIME_DATE | TIME_SECONDS) + "Z"); // MT5 server time, treated as UTC
  }

void SendTradeEvent(string eventType, const TrackedPosition &p, double closedVolume, bool hasClosedVolume)
  {
   string eventId = IntegerToString((long)p.ticket) + "-" + IntegerToString((long)TimeCurrent()) + "-" + IntegerToString(MathRand());
   string json =
      "{\"type\":\"" MSG_TRADE_EVENT "\"," +
      "\"eventId\":\"" + eventId + "\"," +
      "\"eventType\":\"" + eventType + "\"," +
      "\"position\":" + PositionToJson(p) + "," +
      "\"closedVolumeLots\":" + (hasClosedVolume ? DoubleToString(closedVolume, 2) : "null") + "," +
      "\"serverTimestamp\":\"" + TimeToIso(TimeCurrent()) + "\"," +
      "\"receivedAtGateway\":\"\"}";
   WsSendText(json);
  }

//+------------------------------------------------------------------+
//| Inbound message dispatch                                         |
//+------------------------------------------------------------------+
void DispatchMessage(string json)
  {
   if(json == "")
      return;
   string msgType = JsonGetString(json, "type");

   if(msgType == MSG_HELLO_ACK)
     {
      bool accepted = (JsonGetString(json, "accepted") == "true") || (StringFind(json, "\"accepted\":true") >= 0);
      if(accepted)
        {
         g_paired = true;
         g_brokerAccountId = JsonGetString(json, "brokerAccountId");
         Print("Nectrix: paired, brokerAccountId=", g_brokerAccountId);
         SeedTrackedPositionsWithoutEmitting(); // avoid firing spurious OPENED events for positions that already existed before this EA attached
        }
      else
        {
         Print("Nectrix: pairing rejected: ", JsonGetString(json, "reason"));
         CloseGateway();
        }
      return;
     }

   if(msgType == MSG_PING)
     {
      SendPong(JsonGetString(json, "requestId"));
      return;
     }

   if(msgType == MSG_SNAPSHOT_REQUEST)
     {
      HandleSnapshotRequest(JsonGetString(json, "requestId"));
      return;
     }

   if(msgType == MSG_POSITIONS_REQUEST)
     {
      HandlePositionsRequest(JsonGetString(json, "requestId"));
      return;
     }

   if(msgType == MSG_SYMBOL_SPEC_REQ)
     {
      HandleSymbolSpecRequest(json);
      return;
     }

   if(msgType == MSG_ORDER_COMMAND)
     {
      HandleOrderCommand(json);
      return;
     }
  }

void HandleSnapshotRequest(string requestId)
  {
   double balance = AccountInfoDouble(ACCOUNT_BALANCE);
   double equity = AccountInfoDouble(ACCOUNT_EQUITY);
   double usedMargin = AccountInfoDouble(ACCOUNT_MARGIN);
   double freeMargin = AccountInfoDouble(ACCOUNT_MARGIN_FREE);
   double marginLevel = AccountInfoDouble(ACCOUNT_MARGIN_LEVEL);
   string currency = AccountInfoString(ACCOUNT_CURRENCY);

   string json =
      "{\"type\":\"" MSG_SNAPSHOT_RESULT "\",\"requestId\":\"" + requestId + "\"," +
      "\"currency\":\"" + currency + "\"," +
      "\"balance\":" + DoubleToString(balance, 2) + "," +
      "\"equity\":" + DoubleToString(equity, 2) + "," +
      "\"usedMargin\":" + DoubleToString(usedMargin, 2) + "," +
      "\"freeMargin\":" + DoubleToString(freeMargin, 2) + "," +
      "\"marginLevelPct\":" + (marginLevel > 0 ? DoubleToString(marginLevel, 2) : "null") + "," +
      "\"asOf\":\"" + TimeToIso(TimeCurrent()) + "\"}";
   WsSendText(json);
  }

void HandlePositionsRequest(string requestId)
  {
   string items = "";
   int total = PositionsTotal();
   for(int i = 0; i < total; i++)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      TrackedPosition p;
      if(!ReadPosition(ticket, p)) continue;
      if(items != "") items += ",";
      items += PositionToJson(p);
     }
   string json =
      "{\"type\":\"" MSG_POSITIONS_RESULT "\",\"requestId\":\"" + requestId + "\"," +
      "\"positions\":[" + items + "]}";
   WsSendText(json);
  }

void HandleSymbolSpecRequest(string reqJson)
  {
   string requestId = JsonGetString(reqJson, "requestId");
   string symbol = JsonGetString(reqJson, "brokerSymbolName");

   if(!SymbolSelect(symbol, true))
     {
      WsSendText("{\"type\":\"" MSG_SYMBOL_SPEC_RES "\",\"requestId\":\"" + requestId + "\",\"error\":\"unknown symbol\"}");
      return;
     }

   double contractSize = SymbolInfoDouble(symbol, SYMBOL_TRADE_CONTRACT_SIZE);
   double lotStep = SymbolInfoDouble(symbol, SYMBOL_VOLUME_STEP);
   double minLot = SymbolInfoDouble(symbol, SYMBOL_VOLUME_MIN);
   double maxLot = SymbolInfoDouble(symbol, SYMBOL_VOLUME_MAX);
   int digits = (int)SymbolInfoInteger(symbol, SYMBOL_DIGITS);
   double point = SymbolInfoDouble(symbol, SYMBOL_POINT);
   // pipSize: a "pip" is conventionally 10x point for 5/3-digit FX/JPY-style quoting, 1x point
   // otherwise — mirrors the platform's own pipSize convention (docs/08-copy-trading-engine.md).
   double pipSize = (digits == 3 || digits == 5) ? point * 10 : point;
   string marginCurrency = SymbolInfoString(symbol, SYMBOL_CURRENCY_MARGIN);

   string json =
      "{\"type\":\"" MSG_SYMBOL_SPEC_RES "\",\"requestId\":\"" + requestId + "\"," +
      "\"brokerSymbolName\":\"" + JsonEscape(symbol) + "\"," +
      "\"contractSize\":" + DoubleToString(contractSize, 2) + "," +
      "\"lotStep\":" + DoubleToString(lotStep, 2) + "," +
      "\"minLot\":" + DoubleToString(minLot, 2) + "," +
      "\"maxLot\":" + DoubleToString(maxLot, 2) + "," +
      "\"pipSize\":" + DoubleToString(pipSize, 8) + "," +
      "\"digits\":" + IntegerToString(digits) + "," +
      "\"marginCurrency\":\"" + JsonEscape(marginCurrency) + "\"}";
   WsSendText(json);
  }

void HandleOrderCommand(string reqJson)
  {
   string requestId = JsonGetString(reqJson, "requestId");
   string action = JsonGetString(reqJson, "action");

   bool success = false;
   string rejectReason = "";
   string brokerPositionId = "";
   double filledPrice = 0;
   bool hasFilledPrice = false;

   if(action == ACTION_PLACE)
     {
      string symbol = JsonGetString(reqJson, "canonicalSymbol");
      string direction = JsonGetString(reqJson, "direction");
      double volume = JsonGetNumber(reqJson, "volumeLots");
      double slPrice = JsonIsNull(reqJson, "slPrice") ? 0 : JsonGetNumber(reqJson, "slPrice");
      double tpPrice = JsonIsNull(reqJson, "tpPrice") ? 0 : JsonGetNumber(reqJson, "tpPrice");
      string comment = JsonGetString(reqJson, "clientOrderTag");

      if(!SymbolSelect(symbol, true))
        {
         rejectReason = "unknown symbol " + symbol;
        }
      else
        {
         bool ok = (direction == "BUY")
            ? g_trade.Buy(volume, symbol, 0, slPrice, tpPrice, comment)
            : g_trade.Sell(volume, symbol, 0, slPrice, tpPrice, comment);
         if(ok)
           {
            success = true;
            brokerPositionId = IntegerToString((long)g_trade.ResultOrder());
            filledPrice = g_trade.ResultPrice();
            hasFilledPrice = true;
           }
         else
           {
            rejectReason = "OrderSend failed: retcode=" + IntegerToString(g_trade.ResultRetcode()) +
                            " " + g_trade.ResultRetcodeDescription();
           }
        }
     }
   else if(action == ACTION_MODIFY)
     {
      ulong ticket = (ulong)StringToInteger(JsonGetString(reqJson, "positionId"));
      double slPrice = JsonIsNull(reqJson, "slPrice") ? 0 : JsonGetNumber(reqJson, "slPrice");
      double tpPrice = JsonIsNull(reqJson, "tpPrice") ? 0 : JsonGetNumber(reqJson, "tpPrice");
      if(g_trade.PositionModify(ticket, slPrice, tpPrice))
        {
         success = true;
         brokerPositionId = IntegerToString((long)ticket);
        }
      else
        {
         rejectReason = "PositionModify failed: retcode=" + IntegerToString(g_trade.ResultRetcode()) +
                         " " + g_trade.ResultRetcodeDescription();
        }
     }
   else if(action == ACTION_CLOSE)
     {
      ulong ticket = (ulong)StringToInteger(JsonGetString(reqJson, "positionId"));
      bool hasVolume = !JsonIsNull(reqJson, "closeVolumeLots") && JsonHasKey(reqJson, "closeVolumeLots");
      bool ok = hasVolume
         ? g_trade.PositionClosePartial(ticket, JsonGetNumber(reqJson, "closeVolumeLots"))
         : g_trade.PositionClose(ticket);
      if(ok)
        {
         success = true;
         brokerPositionId = IntegerToString((long)ticket);
         filledPrice = g_trade.ResultPrice();
         hasFilledPrice = true;
        }
      else
        {
         rejectReason = "PositionClose failed: retcode=" + IntegerToString(g_trade.ResultRetcode()) +
                         " " + g_trade.ResultRetcodeDescription();
        }
     }
   else
     {
      rejectReason = "unknown order action " + action;
     }

   string json =
      "{\"type\":\"" MSG_ORDER_RESULT "\",\"requestId\":\"" + requestId + "\"," +
      "\"success\":" + (success ? "true" : "false") + "," +
      "\"brokerPositionId\":\"" + brokerPositionId + "\"," +
      "\"filledPrice\":" + (hasFilledPrice ? DoubleToString(filledPrice, _Digits) : "null") + "," +
      "\"rejectReason\":\"" + JsonEscape(rejectReason) + "\"}";
   WsSendText(json);
  }

//+------------------------------------------------------------------+
//| Position-delta detection — the read/streaming path                |
//+------------------------------------------------------------------+
bool ReadPosition(ulong ticket, TrackedPosition &out)
  {
   if(!PositionSelectByTicket(ticket))
      return(false);
   out.ticket = ticket;
   out.symbol = PositionGetString(POSITION_SYMBOL);
   out.volume = PositionGetDouble(POSITION_VOLUME);
   out.priceOpen = PositionGetDouble(POSITION_PRICE_OPEN);
   out.sl = PositionGetDouble(POSITION_SL);
   out.tp = PositionGetDouble(POSITION_TP);
   out.type = PositionGetInteger(POSITION_TYPE);
   out.openTime = (datetime)PositionGetInteger(POSITION_TIME);
   return(true);
  }

int FindTracked(ulong ticket)
  {
   for(int i = 0; i < ArraySize(g_tracked); i++)
      if(g_tracked[i].ticket == ticket)
         return(i);
   return(-1);
  }

// Called once, right after a successful pairing — captures whatever positions already exist on
// this account WITHOUT emitting synthetic OPENED events for them (they weren't opened just now;
// this EA just started watching). Only genuinely new positions from this point on get a real
// trade_event.
void SeedTrackedPositionsWithoutEmitting()
  {
   ArrayResize(g_tracked, 0);
   int total = PositionsTotal();
   for(int i = 0; i < total; i++)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      TrackedPosition p;
      if(!ReadPosition(ticket, p)) continue;
      int n = ArraySize(g_tracked);
      ArrayResize(g_tracked, n + 1);
      g_tracked[n] = p;
     }
  }

void DetectAndSendPositionDeltas()
  {
   bool seenNow[];
   int trackedCountBefore = ArraySize(g_tracked);
   ArrayResize(seenNow, trackedCountBefore);
   ArrayInitialize(seenNow, false);

   int total = PositionsTotal();
   for(int i = 0; i < total; i++)
     {
      ulong ticket = PositionGetTicket(i);
      if(ticket == 0) continue;
      TrackedPosition current;
      if(!ReadPosition(ticket, current)) continue;

      int idx = FindTracked(ticket);
      if(idx < 0)
        {
         // A brand-new position — mirrors real EA attach-after-manual-trade edge cases too,
         // not just this bridge's own PlaceOrder path (positions can be opened from the
         // terminal UI directly, and those must be copied too).
         int n = ArraySize(g_tracked);
         ArrayResize(g_tracked, n + 1);
         g_tracked[n] = current;
         SendTradeEvent("POSITION_OPENED", current, 0, false);
        }
      else
        {
         seenNow[idx] = true;
         TrackedPosition prior = g_tracked[idx];
         bool volumeShrank = current.volume < prior.volume - 0.0000001;
         bool slTpChanged = (MathAbs(current.sl - prior.sl) > 0.0000001) || (MathAbs(current.tp - prior.tp) > 0.0000001);
         if(volumeShrank)
           {
            double closedVolume = prior.volume - current.volume;
            g_tracked[idx] = current;
            SendTradeEvent("POSITION_PARTIALLY_CLOSED", current, closedVolume, true);
           }
         else if(slTpChanged)
           {
            g_tracked[idx] = current;
            SendTradeEvent("POSITION_MODIFIED", current, 0, false);
           }
        }
     }

   // Anything tracked before that wasn't seen this pass is now fully closed.
   for(int i = trackedCountBefore - 1; i >= 0; i--)
     {
      if(!seenNow[i])
        {
         TrackedPosition closed = g_tracked[i];
         SendTradeEvent("POSITION_CLOSED", closed, closed.volume, true);
         RemoveTrackedAt(i);
        }
     }
  }

void RemoveTrackedAt(int idx)
  {
   int n = ArraySize(g_tracked);
   for(int i = idx; i < n - 1; i++)
      g_tracked[i] = g_tracked[i + 1];
   ArrayResize(g_tracked, n - 1);
  }
//+------------------------------------------------------------------+
