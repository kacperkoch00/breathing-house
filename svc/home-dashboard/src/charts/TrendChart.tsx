import { useState } from 'react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

export interface TrendSeriesPoint {
  value: number
  at: string
}

export type TrendTone = 'primary' | 'secondary' | 'accent'

interface TrendChartProps {
  points: TrendSeriesPoint[]
  label: string
  formatValue: (value: number) => string
  tone?: TrendTone
  height?: number
}

const TONE_STROKE: Record<TrendTone, string> = {
  primary: 'var(--color-primary)',
  secondary: 'var(--color-secondary)',
  accent: 'var(--color-accent)',
}

interface ChartRow {
  index: number
  at: string
  value: number
}

function formatChartClock(iso: string, withSeconds: boolean): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    ...(withSeconds ? { second: '2-digit' } : {}),
  })
}

function formatChartStamp(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  return date.toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function minuteKey(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()} ${date.getHours()}:${date.getMinutes()}`
}

function parseActiveIndex(value: unknown): number | null {
  if (typeof value === 'number' && Number.isInteger(value)) return value
  if (typeof value === 'string' && value !== '' && Number.isInteger(Number(value))) {
    return Number(value)
  }
  return null
}

export function TrendChart({
  points,
  label,
  formatValue,
  tone = 'primary',
  height = 240,
}: TrendChartProps) {
  const [hoverIndex, setHoverIndex] = useState<number | null>(null)

  if (points.length < 2) {
    return (
      <div
        className="text-base-content/50 flex flex-col items-center justify-center gap-1 px-4 text-center text-sm"
        style={{ height }}
      >
        <span>Need more readings from this sensor to chart a trend.</span>
        <span className="text-xs opacity-70">At least two points in the last 24 hours.</span>
      </div>
    )
  }

  const stroke = TONE_STROKE[tone]
  const gradientId = `trend-fill-${tone}`
  const withSeconds = points.some((point, index) =>
    points.some(
      (other, otherIndex) =>
        index !== otherIndex && minuteKey(point.at) === minuteKey(other.at),
    ),
  )

  const data: ChartRow[] = points.map((point, index) => ({
    index,
    at: point.at,
    value: point.value,
  }))

  const showAllDots = data.length <= 24
  const values = data.map((row) => row.value)
  const minValue = Math.min(...values)
  const maxValue = Math.max(...values)
  const span = maxValue - minValue
  const pad = Math.max(span * 0.18, Math.abs(maxValue) * 0.02, 0.4)
  const yDomain: [number, number] = [minValue - pad, maxValue + pad]
  const xTicks = data.map((row) => row.index)
  const activeRow =
    hoverIndex != null && hoverIndex >= 0 && hoverIndex < data.length ? data[hoverIndex] : null

  return (
    <div className="w-full" style={{ height }} role="img" aria-label={label}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart
          data={data}
          margin={{ top: 12, right: 12, left: 4, bottom: 20 }}
          onMouseMove={(state) => {
            const next =
              parseActiveIndex(state?.activeTooltipIndex) ??
              parseActiveIndex(state?.activeIndex)
            setHoverIndex(next)
          }}
          onMouseLeave={() => setHoverIndex(null)}
        >
          <defs>
            <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={stroke} stopOpacity={0.35} />
              <stop offset="100%" stopColor={stroke} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid
            stroke="var(--color-base-300)"
            strokeDasharray="3 3"
            vertical={false}
          />
          <XAxis
            dataKey="index"
            type="number"
            domain={[0, Math.max(data.length - 1, 1)]}
            ticks={xTicks}
            allowDecimals={false}
            tickFormatter={(index: number | string) => {
              const row = data[Number(index)]
              return row ? formatChartClock(row.at, withSeconds) : ''
            }}
            tick={{ fill: 'var(--color-base-content)', fillOpacity: 0.45, fontSize: 11 }}
            axisLine={false}
            tickLine={false}
          />
          <YAxis
            domain={yDomain}
            tickCount={5}
            tickFormatter={(value: number) => formatValue(value)}
            width={56}
            tick={{ fill: 'var(--color-base-content)', fillOpacity: 0.45, fontSize: 11 }}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip
            cursor={{ stroke, strokeOpacity: 0.4, strokeWidth: 1 }}
            wrapperStyle={{ outline: 'none' }}
            content={() => {
              if (!activeRow) return null
              return (
                <div className="border-base-300 bg-base-100 rounded-field border px-3 py-2 shadow-lg">
                  <p className="text-base-content/50 font-mono text-[10px] tracking-wide uppercase">
                    {formatChartStamp(activeRow.at)}
                  </p>
                  <p className="metric-value mt-1 text-lg">{formatValue(activeRow.value)}</p>
                </div>
              )
            }}
          />
          <Area
            type="monotone"
            dataKey="value"
            name={label}
            stroke={stroke}
            strokeWidth={2.25}
            fill={`url(#${gradientId})`}
            isAnimationActive={false}
            activeDot={{
              r: 6,
              strokeWidth: 2,
              stroke: 'var(--color-base-100)',
              fill: stroke,
            }}
            dot={showAllDots ? { r: 3.5, fill: stroke, strokeWidth: 0 } : false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}
