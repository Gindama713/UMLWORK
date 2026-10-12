export function countSpaceStatuses(spaces) {
  const counts = {}
  for (const space of spaces) {
    counts[space.type] ??= {}
    counts[space.type][space.status] = (counts[space.type][space.status] || 0) + 1
  }
  return counts
}
