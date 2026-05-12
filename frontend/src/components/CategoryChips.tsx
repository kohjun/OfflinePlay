interface CategoryChipsProps {
  categories: string[]
  value: string
  onChange: (category: string) => void
}

export function CategoryChips({ categories, value, onChange }: CategoryChipsProps) {
  return (
    <div className="chip-row" role="tablist" aria-label="Categories">
      {categories.map((category) => (
        <button
          key={category}
          className={`chip ${value === category ? 'is-active' : ''}`}
          onClick={() => onChange(category)}
          role="tab"
          type="button"
          aria-selected={value === category}
        >
          {category}
        </button>
      ))}
    </div>
  )
}
