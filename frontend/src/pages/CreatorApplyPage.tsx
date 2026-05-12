import { FormEvent, useEffect, useState } from 'react'
import { applyForCreator, getMyCreatorApplication } from '../api/creator'
import { Badge } from '../components/Badge'
import { useToast } from '../hooks/useToast'
import type { CreatorApplication } from '../types'

export function CreatorApplyPage() {
  const { showToast } = useToast()
  const [application, setApplication] = useState<CreatorApplication | null>(null)
  const [reason, setReason] = useState('')
  const [portfolioUrl, setPortfolioUrl] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    getMyCreatorApplication().then(setApplication).catch(() => setApplication(null))
  }, [])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    try {
      await applyForCreator({ reason, portfolioUrl: portfolioUrl || undefined })
      const refreshed = await getMyCreatorApplication()
      setApplication(refreshed)
      showToast({ title: 'Application submitted', tone: 'success' })
    } catch (error) {
      showToast({
        title: 'Application failed',
        message: error instanceof Error ? error.message : 'Please try again.',
        tone: 'danger',
      })
    } finally {
      setSubmitting(false)
    }
  }

  const stepperState = !application
    ? 'pending-submit'
    : application.status === 'PENDING'
    ? 'review'
    : 'result'

  function stepClass(step: 'submit' | 'review' | 'result') {
    if (stepperState === 'pending-submit') {
      return step === 'submit' ? 'apply-step is-current' : 'apply-step'
    }
    if (stepperState === 'review') {
      if (step === 'submit') return 'apply-step is-done'
      if (step === 'review') return 'apply-step is-current'
      return 'apply-step'
    }
    if (step === 'result') return 'apply-step is-current'
    return 'apply-step is-done'
  }

  function resultTone() {
    if (application?.status === 'APPROVED') return 'success'
    if (application?.status === 'REJECTED') return 'danger'
    return 'warning'
  }

  return (
    <main className="page">
      <section className="page-header">
        <div>
          <p className="eyebrow">Creator application</p>
          <h1>Share what you want to build</h1>
        </div>
      </section>
      <ol className="apply-stepper" aria-label="Application progress">
        <li className={stepClass('submit')}>
          <span className="apply-step-dot">1</span>
          <span>Submit</span>
        </li>
        <li className={stepClass('review')}>
          <span className="apply-step-dot">2</span>
          <span>Under review</span>
        </li>
        <li className={stepClass('result')}>
          <span className="apply-step-dot">3</span>
          <span>Result</span>
        </li>
      </ol>
      {application ? (
        <section className="form-section">
          <div className="badge-row">
            <Badge tone={resultTone()}>{application.status}</Badge>
          </div>
          <h2>Your application is on file</h2>
          <p>{application.reason}</p>
          {application.portfolioUrl ? <a href={application.portfolioUrl}>{application.portfolioUrl}</a> : null}
        </section>
      ) : (
        <form className="form-section form-stack" onSubmit={handleSubmit}>
          <label>
            Reason
            <textarea value={reason} onChange={(event) => setReason(event.target.value)} minLength={20} required />
          </label>
          <label>
            Portfolio URL
            <input
              type="url"
              value={portfolioUrl}
              onChange={(event) => setPortfolioUrl(event.target.value)}
              placeholder="https://"
            />
          </label>
          <button className="button button-primary" disabled={submitting} type="submit">
            {submitting ? 'Submitting...' : 'Submit application'}
          </button>
        </form>
      )}
    </main>
  )
}
