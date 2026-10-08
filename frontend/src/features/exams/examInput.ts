import type { Exam } from './examApi'

/**
 * The legacy create/update endpoint still reads startTime/endTime (UTC LocalDateTime) and writes them back onto the schedule.
 * The schedule lives on its own API now, so the editor never edits these: it hands back exactly what the server sent
 * (no timezone helper, no truncation, null stays null) and creating an Exam sends nothing.
 */
export function legacyScheduleFields(exam?: Pick<Exam, 'startTime' | 'endTime'>): { startTime?: string | null; endTime?: string | null } {
  return exam ? { startTime: exam.startTime, endTime: exam.endTime } : {}
}
