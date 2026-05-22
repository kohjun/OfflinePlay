import { useEffect, useMemo, useState } from 'react'
import { listInterests, type Interest } from '../api/taxonomy'

interface InterestPickerProps {
  value: number[]
  onChange: (ids: number[]) => void
  disabled?: boolean
  /** 한 번에 선택 가능한 최대 개수. default 10. backend 는 20 까지 허용. */
  max?: number
}

const CATEGORY_LABEL: Record<string, string> = {
  ACTIVITY: '운동/액티비티',
  CULTURE: '문화/예술',
  FOOD: '푸드/취미',
  GAME: '게임/엔터',
  GROWTH: '자기계발',
  TRAVEL: '여행/탐방',
  SOCIAL: '사교/네트워킹',
}

// 모듈 캐시
let cachedInterests: Interest[] | null = null
let inflight: Promise<Interest[]> | null = null

async function fetchCatalog(): Promise<Interest[]> {
  if (cachedInterests) return cachedInterests
  if (!inflight) {
    inflight = listInterests().then((res) => {
      cachedInterests = res
      return res
    })
  }
  return inflight
}

/**
 * PR147 — 관심사 chip multi-select.
 *
 *  - catalog 는 mount 시 fetch + 모듈 캐시. 새로고침 없이 같은 세션에서 재사용.
 *  - category 별로 grouping 해 chip group 으로 표시.
 *  - max 초과 선택 시 새 클릭은 무시.
 */
export function InterestPicker({ value, onChange, disabled, max = 10 }: InterestPickerProps) {
  const [catalog, setCatalog] = useState<Interest[]>(cachedInterests ?? [])
  const [loading, setLoading] = useState(!cachedInterests)

  useEffect(() => {
    if (cachedInterests) return
    setLoading(true)
    fetchCatalog()
      .then((res) => setCatalog(res))
      .catch(() => setCatalog([]))
      .finally(() => setLoading(false))
  }, [])

  const selected = useMemo(() => new Set(value), [value])

  function toggle(id: number) {
    const next = new Set(selected)
    if (next.has(id)) {
      next.delete(id)
    } else {
      if (next.size >= max) return
      next.add(id)
    }
    onChange(Array.from(next))
  }

  const grouped = useMemo(() => {
    const map = new Map<string, Interest[]>()
    catalog.forEach((i) => {
      if (!map.has(i.category)) map.set(i.category, [])
      map.get(i.category)!.push(i)
    })
    return Array.from(map.entries())
  }, [catalog])

  if (loading) return <p className="muted">관심사 목록을 불러오는 중…</p>

  return (
    <div className="interest-picker stack" aria-label="관심사 선택">
      <span className="muted">관심사는 최대 {max}개까지 선택할 수 있어요.</span>
      {grouped.map(([category, items]) => (
        <fieldset key={category}>
          <legend>{CATEGORY_LABEL[category] ?? category}</legend>
          <div className="interest-chip-row">
            {items.map((i) => {
              const isSelected = selected.has(i.id)
              return (
                <label
                  key={i.id}
                  className={`interest-chip${isSelected ? ' is-selected' : ''}`}
                >
                  <input
                    type="checkbox"
                    checked={isSelected}
                    disabled={disabled || (!isSelected && selected.size >= max)}
                    onChange={() => toggle(i.id)}
                  />
                  {i.label}
                </label>
              )
            })}
          </div>
        </fieldset>
      ))}
    </div>
  )
}
