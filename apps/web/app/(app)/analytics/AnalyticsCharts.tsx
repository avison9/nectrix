"use client";

import { useRouter } from "next/navigation";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { DailyEquityPoint, MonthlyReturn } from "@nectrix/api-client";

/**
 * TICKET-116 — mirrors Nectrix.dc.html's `MASTER · ANALYTICS` (`vMasterAnalytics`, `:548-617`)
 * "Cumulative growth" + "Monthly returns" charts, using real data via `recharts` instead of the
 * mock's own hand-authored SVG paths.
 */
export function EquityCurveChart({ data }: { data: DailyEquityPoint[] }) {
  if (data.length === 0) {
    return (
      <p className="flex h-[230px] items-center justify-center text-[13px] text-[var(--text-2)]">
        No equity data in this period yet.
      </p>
    );
  }
  return (
    <ResponsiveContainer width="100%" height={230}>
      <AreaChart data={data} margin={{ top: 4, right: 4, left: 4, bottom: 0 }}>
        <defs>
          <linearGradient id="eqGradient" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--pos)" stopOpacity={0.16} />
            <stop offset="100%" stopColor="var(--pos)" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis
          dataKey="day"
          tick={{ fontSize: 11, fill: "var(--text-3)" }}
          axisLine={false}
          tickLine={false}
          minTickGap={40}
        />
        <YAxis
          tick={{ fontSize: 11, fill: "var(--text-3)" }}
          axisLine={false}
          tickLine={false}
          width={56}
          tickFormatter={(v: number) => `$${v.toLocaleString()}`}
        />
        <Tooltip
          formatter={(value) => [`$${Number(value).toLocaleString()}`, "Equity"]}
          contentStyle={{
            background: "var(--surface)",
            border: "1px solid var(--border)",
            borderRadius: 10,
            fontSize: 12.5,
          }}
        />
        <Area
          type="monotone"
          dataKey="equity"
          stroke="var(--pos)"
          strokeWidth={2.2}
          fill="url(#eqGradient)"
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

/** Bars are clickable — links to /analytics/months/{month} for that month's real trade drill-down. */
export function MonthlyReturnsChart({ data }: { data: MonthlyReturn[] }) {
  const router = useRouter();
  if (data.length === 0) {
    return (
      <p className="flex h-[210px] items-center justify-center text-[13px] text-[var(--text-2)]">
        Not enough history for a monthly breakdown yet.
      </p>
    );
  }
  return (
    <ResponsiveContainer width="100%" height={210}>
      <BarChart data={data} margin={{ top: 4, right: 4, left: 4, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" vertical={false} />
        <XAxis
          dataKey="month"
          tick={{ fontSize: 10.5, fill: "var(--text-3)" }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tick={{ fontSize: 11, fill: "var(--text-3)" }}
          axisLine={false}
          tickLine={false}
          width={44}
          tickFormatter={(v: number) => `${v}%`}
        />
        <Tooltip
          formatter={(value) => [`${Number(value).toFixed(2)}%`, "Return"]}
          contentStyle={{
            background: "var(--surface)",
            border: "1px solid var(--border)",
            borderRadius: 10,
            fontSize: 12.5,
          }}
        />
        <Bar
          dataKey="returnPct"
          radius={[4, 4, 4, 4]}
          cursor="pointer"
          onClick={(entry) => {
            const month = (entry as unknown as { payload: MonthlyReturn }).payload?.month;
            if (month) router.push(`/analytics/months/${month}`);
          }}
        >
          {data.map((d) => (
            <Cell key={d.month} fill={d.returnPct >= 0 ? "var(--pos)" : "var(--neg)"} />
          ))}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
