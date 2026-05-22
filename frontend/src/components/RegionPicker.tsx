import { useEffect, useMemo, useState } from 'react'
import { getRegionTree, type Sido, type Sigungu } from '../api/taxonomy'

interface RegionPickerProps {
  /** 현재 선택된 region code (시군구 5자리 또는 시도 2자리). null 이면 미선택. */
  value: string | null
  onChange: (code: string | null) => void
  disabled?: boolean
}

/**
 * PR147 — 시/도 → 시/군/구 cascade picker.
 *
 *  - region tree 는 mount 시 1회 fetch + 모듈 캐시.
 *  - value 가 시도 코드 (2자리) 면 시도만 선택된 상태 — 시군구는 비어 있다.
 *  - value 가 시군구 코드 (5자리) 면 prefix 2자리로 sido 자동 매칭.
 */

// 모듈 레벨 캐시 — 한 세션에 한 번만 fetch.
let cachedTree: Sido[] | null = null
let inflight: Promise<Sido[]> | null = null

async function fetchTree(): Promise<Sido[]> {
  if (cachedTree) return cachedTree
  if (!inflight) {
    inflight = getRegionTree().then((res) => {
      cachedTree = res
      return res
    })
  }
  return inflight
}

export function RegionPicker({ value, onChange, disabled }: RegionPickerProps) {
  const [tree, setTree] = useState<Sido[]>(cachedTree ?? [])
  const [loading, setLoading] = useState(!cachedTree)

  useEffect(() => {
    if (cachedTree) return
    setLoading(true)
    fetchTree()
      .then((res) => setTree(res))
      .catch(() => setTree([]))
      .finally(() => setLoading(false))
  }, [])

  const sidoCode = useMemo(() => {
    if (!value) return ''
    return value.length >= 2 ? value.substring(0, 2) : ''
  }, [value])

  const sigunguList: Sigungu[] = useMemo(() => {
    if (!sidoCode) return []
    return tree.find((s) => s.code === sidoCode)?.sigunguList ?? []
  }, [tree, sidoCode])

  function handleSidoChange(next: string) {
    if (!next) onChange(null)
    // 시도만 선택한 시점에는 시군구 미선택 — 시도 code 만 저장.
    else onChange(next)
  }

  function handleSigunguChange(next: string) {
    if (!next) {
      // 시군구 해제 → 시도 코드로 fallback.
      onChange(sidoCode || null)
    } else {
      onChange(next)
    }
  }

  if (loading) {
    return <p className="muted">지역 목록을 불러오는 중…</p>
  }

  return (
    <div className="ct-form-grid-2">
      <label>
        시/도
        <select
          value={sidoCode}
          onChange={(e) => handleSidoChange(e.target.value)}
          disabled={disabled}
        >
          <option value="">선택 안 함</option>
          {tree.map((s) => (
            <option key={s.code} value={s.code}>{s.name}</option>
          ))}
        </select>
      </label>
      <label>
        시/군/구
        <select
          value={value && value.length === 5 ? value : ''}
          onChange={(e) => handleSigunguChange(e.target.value)}
          disabled={disabled || !sidoCode}
        >
          <option value="">전체</option>
          {sigunguList.map((sg) => (
            <option key={sg.code} value={sg.code}>{sg.name}</option>
          ))}
        </select>
      </label>
    </div>
  )
}
