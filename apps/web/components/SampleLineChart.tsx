// A hand-rolled area+line chart for placeholder pages that show sample stat numbers but had no
// chart at all ("Equity chart not available yet.") — matches Nectrix.dc.html's own approach
// (`linePath`/`fEqArea`/`fEqLine`, computed SVG paths over sample data points), just with a fixed
// sample series instead of the mock's own `linePath()` helper. Not real data — callers already
// carry their own "sample data" disclaimer alongside this.
export function SampleLineChart({
  values,
  height = 200,
  gradientId,
}: {
  values: number[];
  height?: number;
  gradientId: string;
}) {
  const width = 800;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const stepX = width / (values.length - 1);
  const points = values.map((v, i) => {
    const x = i * stepX;
    const y = height - ((v - min) / range) * (height - 20) - 10;
    return [x, y] as const;
  });
  const linePath = points.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  const areaPath = `${linePath} L${width},${height} L0,${height} Z`;

  return (
    <svg viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" className="block h-full w-full">
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="var(--pos)" stopOpacity="0.15" />
          <stop offset="1" stopColor="var(--pos)" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={areaPath} fill={`url(#${gradientId})`} />
      <path d={linePath} fill="none" stroke="var(--pos)" strokeWidth={2.2} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
