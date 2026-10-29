export { NTP_CONSTANTS } from './upstream/constants';
export const ClientActionEnum = { enum: { NTP_REQUEST: 'NTP_REQUEST' } };
// Host boundary only: algorithms retain their original epochNow() calls.
export const epochNow = () => (globalThis as any).__hostNowMs;
