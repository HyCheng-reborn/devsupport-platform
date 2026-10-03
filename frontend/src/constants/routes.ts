export const ROUTES = {
  docsCenter: '/docs',
  docsUpload: '/docs/upload',
  chatSessions: '/chat',
  chatSessionDetail: (id: string) => `/chat/${id}` as const,
  cases: '/cases',
  evalResults: '/eval-results',
  settings: '/settings',
  // Legacy routes kept for redirect compatibility
  interview: '/interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  knowledgebaseUpload: '/knowledgebase/upload',
} as const;

export const ROUTE_PATTERNS = {
  chatSessionDetail: 'chat/:sessionId',
  // Legacy patterns kept for redirect compatibility
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
} as const;
