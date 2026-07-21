//+------------------------------------------------------------------+
//|                                            NectrixBridgeMT4.mq4   |
//|                                                          Nectrix  |
//|                                                                   |
//| TICKET-121 — MQL4 has no native Socket*() functions at all        |
//| (confirmed against MQL4's own reference docs: Socket* was added   |
//| in MQL5 only, never backported — a genuine platform gap, not a    |
//| bug; the original version of this file assumed otherwise and      |
//| failed nine real MetaEditor compile errors, see this ticket's own |
//| plan). So unlike NectrixBridgeMT5.mq5's persistent-WebSocket       |
//| design, this EA speaks HTTP long-polling via MQL4's native        |
//| WebRequest(): POST /ea/hello once at startup (the handshake),     |
//| POST /ea/poll on every OnTimer tick to fetch pending gateway->EA  |
//| messages, POST /ea/events for every EA->gateway message           |
//| (trade_event/*_result/pong) — see apps/mt5-bridge-gateway/        |
//| internal/eabridge/httphandler.go, whose /ea/hello, /ea/poll,      |
//| /ea/events routes this file talks to. The wire message shapes     |
//| (hello/hello_ack/trade_event/etc — see internal/eabridge/wire.go) |
//| are byte-for-byte identical to MT5's own — only the transport      |
//| differs; internal/eabridge/httpconn.go makes the two transports   |
//| genuinely indistinguishable to the Go gateway's own session/       |
//| request-correlation logic.                                        |
//|                                                                   |
//| Field-for-field, message-for-message the same protocol as before  |
//| — the ONLY real difference from a from-scratch rewrite is the     |
//| connection layer; MT4's own ticket-based OrderSend/OrderModify/   |
//| OrderClose trade model (no CTrade class, no netting) is untouched.|
//|                                                                   |
//| WebRequest() requires the gateway's hello/poll/events URLs to be  |
//| allow-listed in the terminal (Tools -> Options -> Expert Advisors |
//| -> "Allow WebRequest for listed URL", or the .ini-based headless  |
//| equivalent apps/mt-terminal-host/terminal-image/entrypoint.sh     |
//| attempts — UNVERIFIED against a real terminal boot, see that      |
//| script's own comment). Without it, every WebRequest() call fails  |
//| with error 4060 (function not allowed) — HttpPostJson below logs  |
//| that distinctly so a misconfigured allow-list is never mistaken   |
//| for a gateway outage.                                              |
//+------------------------------------------------------------------+
#property copyright "Nectrix"
#property link      "https://nectrix.example"
#property version   "1.00"
#property strict

//--- Inputs — pasted by the user from the POST /api/v1/broker-accounts/mt4 response
//    (BrokerAccountMtController.LinkResponse: pairingToken + gatewayUrl).
input string InpPairingToken   = "";                 // Pairing token (from the link response)
input string InpGatewayHost    = "127.0.0.1";         // mt5-bridge-gateway host (from gatewayUrl)
input int    InpGatewayPort    = 8092;                // mt5-bridge-gateway port (from gatewayUrl)
input string InpGatewayPath    = "/ea";               // Base HTTP path (from gatewayUrl) — /hello, /poll, /events are appended
input int    InpPollMs         = 2000;                // OnTimer interval, ms — each tick makes a real blocking WebRequest() poll call, so this is deliberately less aggressive than a raw socket-read poll would need to be
input int    InpPollTimeoutMs  = 20000;               // WebRequest() timeout for /ea/poll — must be >= the gateway's own httpPollMaxWait (20s) plus network slack
input int    InpReconnectSec   = 5;                   // Reconnect backoff after a failed/rejected hello
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

//--- HTTP long-poll session state (replaces a raw socket handle — there is
//    no persistent connection to hold onto between OnTimer ticks).
bool     g_paired             = false; // hello_ack{accepted:true} received
string   g_sessionToken       = "";
string   g_brokerAccountId    = "";
datetime g_lastConnectAttempt = 0;

//--- Position tracking, to derive OPENED/MODIFIED/PARTIALLY_CLOSED/CLOSED deltas — MT4: one
//    "position" == one open OP_BUY/OP_SELL order/ticket (no netting), so this is simpler than
//    MT5's model but the delta-detection approach is identical.
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
   ArrayResize(g_tracked, 0);
   EventSetMillisecondTimer(InpPollMs);
   if(InpPairingToken == "")
     {
      Print("Nectrix: InpPairingToken is required — paste it from the broker-linking response.");
      return(INIT_PARAMETERS_INCORRECT);
     }
   ConnectGateway();
   return(INIT_SUCCEEDED); // a failed initial hello retries from OnTimer, not fatal to EA load
  }

//+------------------------------------------------------------------+
void OnDeinit(const int reason)
  {
   EventKillTimer();
   g_paired = false;
   g_sessionToken = "";
  }

//+------------------------------------------------------------------+
void OnTimer()
  {
   if(!g_paired)
     {
      if(TimeCurrent() - g_lastConnectAttempt >= InpReconnectSec)
         ConnectGateway();
      return;
     }
   PollGateway();
   if(g_paired) // PollGateway may have cleared this on a 401 (expired/idle-timed-out session)
      DetectAndSendPositionDeltas();
  }

//+------------------------------------------------------------------+
//| HTTP transport                                                    |
//+------------------------------------------------------------------+
string GatewayUrl(string subPath)
  {
   return("http://" + InpGatewayHost + ":" + IntegerToString(InpGatewayPort) + InpGatewayPath + subPath);
  }

//| Issues one POST with a JSON body via WebRequest() — returns the real
//| HTTP status code, or -1 on a transport-level failure (most commonly
//| error 4060, "function not allowed", meaning the URL isn't allow-listed).
int HttpPostJson(string url, string jsonBody, int timeoutMs, string &outResponseBody)
  {
   uchar data[];
   int len = StringToCharArray(jsonBody, data, 0, StringLen(jsonBody), CP_UTF8) - 1;
   ArrayResize(data, MathMax(len, 0));
   uchar result[];
   string resultHeaders;
   ResetLastError();
   int status = WebRequest("POST", url, "Content-Type: application/json\r\n", timeoutMs, data, result, resultHeaders);
   if(status == -1)
     {
      int err = GetLastError();
      if(err == 4060)
         Print("Nectrix: WebRequest blocked (error 4060) — add ", url, " to Tools > Options > Expert Advisors > \"Allow WebRequest for listed URL\"");
      else
         Print("Nectrix: WebRequest to ", url, " failed, error ", err);
      outResponseBody = "";
      return(-1);
     }
   outResponseBody = CharArrayToString(result, 0, ArraySize(result), CP_UTF8);
   return(status);
  }

//| The handshake — HTTP counterpart of the WebSocket EA's ConnectGateway
//| (SendHttpUpgrade + SendHello combined into one request/response).
void ConnectGateway()
  {
   g_lastConnectAttempt = TimeCurrent();
   g_paired = false;
   g_sessionToken = "";
   g_brokerAccountId = "";

   string login = IntegerToString(AccountNumber());
   string server = AccountServer();
   string currency = AccountCurrency();
   string helloJson =
      "{\"type\":\"" MSG_HELLO "\"," +
      "\"pairingToken\":\"" + JsonEscape(InpPairingToken) + "\"," +
      "\"platform\":\"MT4\"," +
      "\"login\":\"" + login + "\"," +
      "\"server\":\"" + JsonEscape(server) + "\"," +
      "\"currency\":\"" + JsonEscape(currency) + "\"}";

   string response;
   int status = HttpPostJson(GatewayUrl("/hello"), helloJson, 10000, response);
   if(status != 200)
      return; // HttpPostJson already logged the reason; retried on the next OnTimer reconnect tick

   bool accepted = (StringFind(response, "\"accepted\":true") >= 0);
   if(!accepted)
     {
      Print("Nectrix: pairing rejected: ", JsonGetString(response, "reason"));
      return;
     }

   g_paired = true;
   g_sessionToken = JsonGetString(response, "sessionToken");
   g_brokerAccountId = JsonGetString(response, "brokerAccountId");
   Print("Nectrix: paired, brokerAccountId=", g_brokerAccountId);
   SeedTrackedPositionsWithoutEmitting();
  }

//| One blocking /ea/poll call (up to InpPollTimeoutMs), dispatching every
//| message the gateway had queued — the long-polling counterpart of the
//| WebSocket EA's PumpSocket, run once per OnTimer tick.
void PollGateway()
  {
   string body = "{\"sessionToken\":\"" + JsonEscape(g_sessionToken) + "\"}";
   string response;
   int status = HttpPostJson(GatewayUrl("/poll"), body, InpPollTimeoutMs, response);
   if(status == 401)
     {
      Print("Nectrix: gateway no longer recognizes this session (expired/idle-timed-out) — reconnecting");
      g_paired = false;
      g_sessionToken = "";
      return;
     }
   if(status != 200)
      return; // transient failure — retried on the next tick, session stays paired

   string messages[];
   int count = JsonExtractArray(response, "messages", messages);
   for(int i = 0; i < count; i++)
      DispatchMessage(messages[i]);
  }

//| Sends one EA->gateway message — the long-polling counterpart of the
//| WebSocket EA's WsSendText.
void PostEvent(string payloadJson)
  {
   string body = "{\"sessionToken\":\"" + JsonEscape(g_sessionToken) + "\",\"message\":" + payloadJson + "}";
   string response;
   HttpPostJson(GatewayUrl("/events"), body, 10000, response);
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

//| Extracts the raw JSON object strings out of a top-level array-of-objects
//| field (e.g. "messages":[{...},{...}]) — a depth-and-string-aware bracket
//| scan, not a general parser (same "targeted extraction over a small fixed
//| set of known shapes" philosophy as JsonGetString/JsonGetNumber above),
//| since this only ever needs to split /ea/poll's own "messages" array into
//| individually-dispatchable object strings. String-awareness matters even
//| though today's message shapes never nest braces (an unescaped '{'/'}'
//| inside a JSON string value, e.g. a bracket in a clientOrderTag, would
//| otherwise desync a naive brace-counting scan).
int JsonExtractArray(string json, string key, string &out[])
  {
   ArrayResize(out, 0);
   string needle = "\"" + key + "\"";
   int keyPos = StringFind(json, needle);
   if(keyPos < 0) return(0);
   int colon = StringFind(json, ":", keyPos);
   if(colon < 0) return(0);
   int bracketStart = StringFind(json, "[", colon);
   if(bracketStart < 0) return(0);

   int pos = bracketStart + 1;
   int n = StringLen(json);
   int count = 0;
   while(pos < n)
     {
      while(pos < n)
        {
         ushort ws = StringGetCharacter(json, pos);
         if(ws == ' ' || ws == ',' || ws == '\n' || ws == '\r' || ws == '\t')
            pos++;
         else
            break;
        }
      if(pos >= n || StringGetCharacter(json, pos) == ']')
         break;
      if(StringGetCharacter(json, pos) != '{')
         break; // malformed — bail out with whatever was already extracted

      int depth = 0;
      int objStart = pos;
      bool inString = false;
      while(pos < n)
        {
         ushort c = StringGetCharacter(json, pos);
         if(inString)
           {
            if(c == '\\')
               pos++; // skip the escaped character too, so a \" doesn't end the string early
            else if(c == '"')
               inString = false;
           }
         else
           {
            if(c == '"')
               inString = true;
            else if(c == '{')
               depth++;
            else if(c == '}')
              {
               depth--;
               if(depth == 0)
                 {
                  pos++;
                  break;
                 }
              }
           }
         pos++;
        }
      ArrayResize(out, count + 1);
      out[count] = StringSubstr(json, objStart, pos - objStart);
      count++;
     }
   return(count);
  }

//+------------------------------------------------------------------+
//| Outbound message builders                                        |
//+------------------------------------------------------------------+
void SendPong(string requestId)
  {
   PostEvent("{\"type\":\"" MSG_PONG "\",\"requestId\":\"" + requestId + "\"}");
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
   PostEvent(json);
  }

//+------------------------------------------------------------------+
//| Inbound message dispatch                                         |
//+------------------------------------------------------------------+
void DispatchMessage(string json)
  {
   if(json == "")
      return;
   string msgType = JsonGetString(json, "type");

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
   PostEvent(json);
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
   PostEvent(json);
  }

void HandleSymbolSpecRequest(string reqJson)
  {
   string requestId = JsonGetString(reqJson, "requestId");
   string symbol = JsonGetString(reqJson, "brokerSymbolName");

   if(MarketInfo(symbol, MODE_TRADEALLOWED) == 0 && MarketInfo(symbol, MODE_LOTSIZE) == 0)
     {
      PostEvent("{\"type\":\"" MSG_SYMBOL_SPEC_RES "\",\"requestId\":\"" + requestId + "\",\"error\":\"unknown symbol\"}");
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
   PostEvent(json);
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
   PostEvent(json);
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
