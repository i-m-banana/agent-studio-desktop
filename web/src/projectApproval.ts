export function projectApproval(target: string): { project: string; root: string; file?: string; isolated: boolean } | undefined {
  if (!target.startsWith('PROJECT:')) return undefined
  const project = target.slice('PROJECT:'.length).split('|ID:')[0]
  const root = target.split('|WORKSPACE:')[1]?.split('#')[0] ?? ''
  const file = target.split('|FILE:')[1]?.split('|')[0]
  return { project, root, file, isolated: target.includes('|NETWORK:none|HOST_DATA:none') }
}
