//+------------------------------------------------------------------+
//|                                            NectrixBridgeMT4.mq4   |
//|                                                          Nectrix  |
//|                                                                   |
//| TICKET-102 — the MT4 half of the EA-bridge strategy, pulled       |
//| forward from its original Phase-3 scope (TICKET-311) alongside    |
//| MT5, at the user's request. Field-for-field, message-for-message  |
//| the same wire protocol as NectrixBridgeMT5.mq5 (see that file's   |
//| header for the full protocol rationale and apps/mt5-bridge-gateway|
//| /internal/eabridge's wire.go, which this must match exactly) — the|
//| ONLY real differences here are MQL4's order/trading API shape     |
//| (ticket-based OrderSend/OrderModify/OrderClose, no CTrade class,  |
//| no netting position model) versus MQL5's. Both EAs dial into the  |
//| SAME Go gateway process/port; the gateway tells them apart via    |
//| the "platform" field in the hello handshake.                      |
//|                                                                   |
//| Socket*/CryptEncode functions are available in MQL4 since the     |
//| ~2014 MetaQuotes unification of the MQL4/MQL5 runtimes (build     |
//| 600+) — this file assumes a modern MT4 terminal, consistent with  |
//| this ticket's own EA-bridge strategy (a genuinely old MT4 build   |
//| couldn't run either language's socket API at all).                |
//+------------------------------------------------------------------+
#property copyright "Nectrix"
#property link      "https://nectrix.example"
#property version   "1.00"
#property strict

//--- Inputs — pasted by the user from the POST /api/v1/broker-accounts/mt4 response.
input string InpPairingToken   = "";                 // Pairing token (from the link response)
input string InpGatewayHost    = "127.0.0.1";         // mt5-bridge-gateway host (from gatewayUrl)
input int    InpGatewayPort    = 8092;                // mt5-bridge-gateway port (from gatewayUrl)
input string InpGatewayPath    = "/ea/ws";            // WebSocket path (from gatewayUrl)
input int    InpPollMs         = 500;                 // OnTimer poll interval, ms
input int    InpReconnectSec   = 5;                   // Reconnect backoff after a dropped socket
input int    InpSlippagePoints = 30;                  // Slippage tolerance for OrderSend/OrderClose

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

//--- Socket / WebSocket session state
int    g_socket            = INVALID_HANDLE;
bool   g_httpUpgraded       = false;
bool   g_paired             = false;
string g_brokerAccountId    = "";
uchar  g_recvBuf[];
datetime g_lastConnectAttempt = 0;

//--- Position tracking (MT4: one "position" == one open OP_BUY/OP_SELL order/ticket — no netting,
//    so this is simpler than MT5's model but the delta-detection approach is identical).
struct TrackedPosition
  {
   int    ticket;
   string symbol;
   double volume;
   double priceOpen;
   double sl;
   double tp;
   int    type; // OP_BUY / OP_SELL
   datetime openTime;
  };
TrackedPosition g_tracked[];

//+------------------------------------------------------------------+
int OnInit()
  {
   ArrayResize(g_recvBuf, 0);
   ArrayResize(g_tracked, 0);
   EventSetMillisecond(InpPollMs);
   if(InpPairingToken == "")
     {
      Print("Nectrix: InpPairingToken is required — paste it from the broker-linking response.");
      return(INIT_PARAMETERS_INCORRECT);
     }
   ConnectGateway();
   return(INIT_SUCCEEDED);
  }

void OnDeinit(const int reason)
  {
   EventKillTimer();
   CloseGateway();
  }

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
   CloseGateway();

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
//| HTTP Upgrade handshake (RFC 6455 §4) — identical to the MT5 EA's  |
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
   int len = StringToCharArray(req, reqBytes, 0, StringLen(req), CP_UTF8) - 1;
   return(SocketSend(g_socket, reqBytes, len) == len);
  }

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
   return(true); // see NectrixBridgeMT5.mq5's identical note on why Sec-WebSocket-Accept isn't re-verified
  }

string GenerateWebSocketKeyBase64()
  {
   uchar raw[16];
   for(int i = 0; i < 16; i++)
      raw[i] = (uchar)(MathRand() % 256);
   uchar encoded[];
   uchar key[];
   CryptEncode(CRYPT_BASE64, raw, key, encoded);
   return(CharArrayToString(encoded, 0, ArraySize(encoded), CP_UTF8));
  }

//+------------------------------------------------------------------+
//| WebSocket framing — byte-for-byte identical to the MT5 EA's       |
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
      frame[0] = 0x81;
      frame[1] = (uchar)(0x80 | bodyLen);
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
      headerLen = 10;
      ArrayResize(frame, headerLen);
      frame[0] = 0x81;
      frame[1] = 0x80 | 127;
      for(int i = 0; i < 4; i++)
         frame[2 + i] = 0;
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

bool TryExtractFrame(uchar &buf[], string &outPayload, int &outConsumed)
  {
   int n = ArraySize(buf);
   if(n < 2)
      return(false);

   uchar byte0 = buf[0];
   uchar byte1 = buf[1];
   int opcode = byte0 & 0x0F;
   bool masked = (byte1 & 0x80) != 0;
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
      return(false);

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

   if(opcode == 0x8)
     {
      Print("Nectrix: gateway sent a WebSocket close frame");
      outPayload = "";
      CloseGateway();
      return(false);
     }
   if(opcode == 0x9 || opcode == 0xA)
     {
      outPayload = "";
      return(true);
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
//| Minimal targeted JSON extraction — identical to the MT5 EA's      |
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
   string login = IntegerToString(AccountNumber());
   string server = AccountServer();
   string currency = AccountCurrency();
   string json =
      "{\"type\":\"" MSG_HELLO "\"," +
      "\"pairingToken\":\"" + JsonEscape(InpPairingToken) + "\"," +
      "\"platform\":\"MT4\"," +
      "\"login\":\"" + login + "\"," +
      "\"server\":\"" + JsonEscape(server) + "\"," +
      "\"currency\":\"" + JsonEscape(currency) + "\"}";
   WsSendText(json);
  }

void SendPong(string requestId)
  {
   WsSendText("{\"type\":\"" MSG_PONG "\",\"requestId\":\"" + requestId + "\"}");
  }

string PositionToJson(const TrackedPosition &p)
  {
   string direction = (p.type == OP_BUY) ? "BUY" : "SELL";
   string json =
      "{\"brokerPositionId\":\"" + IntegerToString(p.ticket) + "\"," +
      "\"canonicalSymbol\":\"" + JsonEscape(p.symbol) + "\"," +
      "\"assetClass\":\"FX\"," +
      "\"direction\":\"" + direction + "\"," +
      "\"volumeLots\":" + DoubleToString(p.volume, 2) + "," +
      "\"openPrice\":" + DoubleToString(p.priceOpen, Digits) + "," +
      "\"currentSlPrice\":" + (p.sl > 0 ? DoubleToString(p.sl, Digits) : "null") + "," +
      "\"currentTpPrice\":" + (p.tp > 0 ? DoubleToString(p.tp, Digits) : "null") + "," +
      "\"openedAt\":\"" + TimeToIso(p.openTime) + "\"}";
   return(json);
  }

string TimeToIso(datetime t)
  {
   return(TimeToString(t, TIME_DATE | TIME_SECONDS) + "Z");
  }

void SendTradeEvent(string eventType, const TrackedPosition &p, double closedVolume, bool hasClosedVolume)
  {
   string eventId = IntegerToString(p.ticket) + "-" + IntegerToString((long)TimeCurrent()) + "-" + IntegerToString(MathRand());
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
      bool accepted = (StringFind(json, "\"accepted\":true") >= 0);
      if(accepted)
        {
         g_paired = true;
         g_brokerAccountId = JsonGetString(json, "brokerAccountId");
         Print("Nectrix: paired, brokerAccountId=", g_brokerAccountId);
         SeedTrackedPositionsWithoutEmitting();
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
   double balance = AccountBalance();
   double equity = AccountEquity();
   double usedMargin = AccountMargin();
   double freeMargin = AccountFreeMargin();
   double marginLevel = (usedMargin > 0) ? (equity / usedMargin * 100.0) : 0;
   string currency = AccountCurrency();

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
   int total = OrdersTotal();
   for(int i = 0; i < total; i++)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(OrderType() != OP_BUY && OrderType() != OP_SELL) continue; // pending orders aren't positions
      TrackedPosition p;
      ReadCurrentOrderAsPosition(p);
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

   if(MarketInfo(symbol, MODE_TRADEALLOWED) == 0 && MarketInfo(symbol, MODE_LOTSIZE) == 0)
     {
      WsSendText("{\"type\":\"" MSG_SYMBOL_SPEC_RES "\",\"requestId\":\"" + requestId + "\",\"error\":\"unknown symbol\"}");
      return;
     }

   double contractSize = MarketInfo(symbol, MODE_LOTSIZE);
   double lotStep = MarketInfo(symbol, MODE_LOTSTEP);
   double minLot = MarketInfo(symbol, MODE_MINLOT);
   double maxLot = MarketInfo(symbol, MODE_MAXLOT);
   int digits = (int)MarketInfo(symbol, MODE_DIGITS);
   double point = MarketInfo(symbol, MODE_POINT);
   double pipSize = (digits == 3 || digits == 5) ? point * 10 : point;
   string marginCurrency = AccountCurrency(); // MT4 has no per-symbol margin-currency MarketInfo mode

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

      if(MarketInfo(symbol, MODE_LOTSIZE) == 0)
        {
         rejectReason = "unknown symbol " + symbol;
        }
      else
        {
         int cmd = (direction == "BUY") ? OP_BUY : OP_SELL;
         double price = (cmd == OP_BUY) ? MarketInfo(symbol, MODE_ASK) : MarketInfo(symbol, MODE_BID);
         int ticket = OrderSend(symbol, cmd, volume, price, InpSlippagePoints, slPrice, tpPrice, comment, 0, 0, clrNONE);
         if(ticket > 0)
           {
            success = true;
            brokerPositionId = IntegerToString(ticket);
            if(OrderSelect(ticket, SELECT_BY_TICKET))
              {
               filledPrice = OrderOpenPrice();
               hasFilledPrice = true;
              }
           }
         else
           {
            rejectReason = "OrderSend failed: error " + IntegerToString(GetLastError());
           }
        }
     }
   else if(action == ACTION_MODIFY)
     {
      int ticket = (int)StringToInteger(JsonGetString(reqJson, "positionId"));
      double slPrice = JsonIsNull(reqJson, "slPrice") ? 0 : JsonGetNumber(reqJson, "slPrice");
      double tpPrice = JsonIsNull(reqJson, "tpPrice") ? 0 : JsonGetNumber(reqJson, "tpPrice");
      if(OrderSelect(ticket, SELECT_BY_TICKET) && OrderModify(ticket, OrderOpenPrice(), slPrice, tpPrice, 0, clrNONE))
        {
         success = true;
         brokerPositionId = IntegerToString(ticket);
        }
      else
        {
         rejectReason = "OrderModify failed: error " + IntegerToString(GetLastError());
        }
     }
   else if(action == ACTION_CLOSE)
     {
      int ticket = (int)StringToInteger(JsonGetString(reqJson, "positionId"));
      bool hasVolume = !JsonIsNull(reqJson, "closeVolumeLots") && JsonHasKey(reqJson, "closeVolumeLots");
      if(OrderSelect(ticket, SELECT_BY_TICKET))
        {
         double closeVolume = hasVolume ? JsonGetNumber(reqJson, "closeVolumeLots") : OrderLots();
         double closePrice = (OrderType() == OP_BUY) ? MarketInfo(OrderSymbol(), MODE_BID) : MarketInfo(OrderSymbol(), MODE_ASK);
         if(OrderClose(ticket, closeVolume, closePrice, InpSlippagePoints, clrNONE))
           {
            success = true;
            brokerPositionId = IntegerToString(ticket);
            filledPrice = closePrice;
            hasFilledPrice = true;
           }
         else
           {
            rejectReason = "OrderClose failed: error " + IntegerToString(GetLastError());
           }
        }
      else
        {
         rejectReason = "unknown ticket " + IntegerToString(ticket);
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
      "\"filledPrice\":" + (hasFilledPrice ? DoubleToString(filledPrice, Digits) : "null") + "," +
      "\"rejectReason\":\"" + JsonEscape(rejectReason) + "\"}";
   WsSendText(json);
  }

//+------------------------------------------------------------------+
//| Position-delta detection — identical shape to the MT5 EA's, over  |
//| MT4's ticket-based order model instead of MT5's netted positions. |
//+------------------------------------------------------------------+
void ReadCurrentOrderAsPosition(TrackedPosition &out)
  {
   out.ticket = OrderTicket();
   out.symbol = OrderSymbol();
   out.volume = OrderLots();
   out.priceOpen = OrderOpenPrice();
   out.sl = OrderStopLoss();
   out.tp = OrderTakeProfit();
   out.type = OrderType();
   out.openTime = OrderOpenTime();
  }

int FindTracked(int ticket)
  {
   for(int i = 0; i < ArraySize(g_tracked); i++)
      if(g_tracked[i].ticket == ticket)
         return(i);
   return(-1);
  }

void SeedTrackedPositionsWithoutEmitting()
  {
   ArrayResize(g_tracked, 0);
   int total = OrdersTotal();
   for(int i = 0; i < total; i++)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(OrderType() != OP_BUY && OrderType() != OP_SELL) continue;
      TrackedPosition p;
      ReadCurrentOrderAsPosition(p);
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

   int total = OrdersTotal();
   for(int i = 0; i < total; i++)
     {
      if(!OrderSelect(i, SELECT_BY_POS, MODE_TRADES)) continue;
      if(OrderType() != OP_BUY && OrderType() != OP_SELL) continue;
      TrackedPosition current;
      ReadCurrentOrderAsPosition(current);

      int idx = FindTracked(current.ticket);
      if(idx < 0)
        {
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
