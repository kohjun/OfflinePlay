export interface Notification {
  id: number
  type: string
  title: string
  message: string
  targetType: string
  targetId: number
  isRead: boolean
  createdAt: string
}
