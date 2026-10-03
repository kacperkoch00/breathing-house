import { mapSparklinePoints, pointsToPolyline } from '../roomDetail'

interface SparklineProps {
  values: number[]
  label: string
  width?: number
  height?: number
  className?: string
}

export function Sparkline({
  values,
  label,
  width = 320,
  height = 72,
  className = 'sparkline',
}: SparklineProps) {
  if (values.length < 2) {
    return null
  }

  const points = mapSparklinePoints(values, width, height)
  const polyline = pointsToPolyline(points)

  return (
    <svg
      className={className}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={label}
      preserveAspectRatio="none"
    >
      <polyline
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinejoin="round"
        strokeLinecap="round"
        points={polyline}
      />
    </svg>
  )
}
