import { useCallback, useEffect, useState } from 'react'
import {
  getChannelAnalytics,
  type CreatorChannelAnalytics,
} from '../api/creatorAnalytics'
import { useToast } from '../hooks/useToast'

interface CreatorAnalyticsCardProps {
  channelId: number
  channelName: string
}

type Range = '30D' | '90D' | 'ALL'

function rangeToParams(range: Range): { from?: string; to?: string } {
  if (range === 'ALL') return {}
  const days = range === '30D' ? 30 : 90
  const now = new Date()
  const from = new Date(now.getTime() - days * 24 * 60 * 60 * 1000)
  return { from: from.toISOString().slice(0, 19), to: now.toISOString().slice(0, 19) }
}

function formatKrw(value: number) {
  return `${value.toLocaleString()}원`
}

/**
 * PR153 — Creator Dashboard 의 채널별 매출/환불 카드.
 *
 *  - 기간 선택 (30일/90일/전체) — 기본 30일.
 *  - 채널 합계 4 metric + 이벤트별 breakdown 테이블.
 *  - 권한 가드는 backend (`owner / STAFF / ADMIN`) 가 담당 — 403 이면 toast.
 */
export function CreatorAnalyticsCard({ channelId, channelName }: CreatorAnalyticsCardProps) {
  const { showToast } = useToast()
  const [range, setRange] = useState<Range>('30D')
  const [data, setData] = useState<CreatorChannelAnalytics | null>(null)
  const [loading, setLoading] = useState(true)

  const fetchData = useCallback(() => {
    setLoading(true)
    getChannelAnalytics(channelId, rangeToParams(range))
      .then((res) => setData(res))
      .catch((err) => {
        showToast({
          title: '매출 분석을 불러오지 못했어요',
          message: err instanceof Error ? err.message : '잠시 후 다시 시도해주세요.',
          tone: 'warning',
        })
        setData(null)
      })
      .finally(() => setLoading(false))
  }, [channelId, range, showToast])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  return (
    <section className="creator-analytics-card" aria-label={`${channelName} 매출 / 환불`}>
      <header className="card-heading-row">
        <h3>매출 / 환불 — {channelName}</h3>
        <nav className="creator-analytics-card__range" role="tablist" aria-label="기간 선택">
          {([
            ['30D', '지난 30일'],
            ['90D', '지난 90일'],
            ['ALL', '전체'],
          ] as Array<[Range, string]>).map(([id, label]) => (
            <button
              key={id}
              type="button"
              role="tab"
              aria-selected={range === id}
              className={`tab-chip${range === id ? ' is-active' : ''}`}
              onClick={() => setRange(id)}
            >
              {label}
            </button>
          ))}
        </nav>
      </header>

      {loading ? (
        <p className="muted">불러오는 중…</p>
      ) : !data ? (
        <p className="muted">표시할 데이터가 없습니다.</p>
      ) : (
        <>
          <ul className="creator-analytics-card__totals">
            <li>
              <span className="muted">총 매출</span>
              <strong>{formatKrw(data.grossRevenue)}</strong>
            </li>
            <li>
              <span className="muted">환불액</span>
              <strong>{formatKrw(data.refundedAmount)}</strong>
            </li>
            <li>
              <span className="muted">순 매출</span>
              <strong>{formatKrw(data.netRevenue)}</strong>
            </li>
            <li>
              <span className="muted">결제 건수</span>
              <strong>{data.paidAttemptCount.toLocaleString()}건</strong>
            </li>
          </ul>
          {data.partialRefundAmount > 0 || data.fullRefundCount > 0 ? (
            <p className="muted creator-analytics-card__refund-detail">
              부분 환불 {formatKrw(data.partialRefundAmount)}
              {' · '}
              전액 환불 {data.fullRefundCount.toLocaleString()}건
            </p>
          ) : null}

          {data.events.length === 0 ? (
            <p className="muted">이벤트별 결제 내역이 없어요.</p>
          ) : (
            <table className="creator-analytics-card__events">
              <thead>
                <tr>
                  <th scope="col">이벤트</th>
                  <th scope="col" className="num">총 매출</th>
                  <th scope="col" className="num">환불</th>
                  <th scope="col" className="num">순 매출</th>
                  <th scope="col" className="num">건수</th>
                </tr>
              </thead>
              <tbody>
                {data.events.map((e) => (
                  <tr key={e.eventId}>
                    <td>{e.eventTitle}</td>
                    <td className="num">{formatKrw(e.grossRevenue)}</td>
                    <td className="num">{formatKrw(e.refundedAmount)}</td>
                    <td className="num">{formatKrw(e.netRevenue)}</td>
                    <td className="num">{e.paidAttemptCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  )
}
