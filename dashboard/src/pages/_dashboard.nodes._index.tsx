import Nodes from '@/features/nodes/components/nodes-list'
import { useOutletContext } from 'react-router'
import type { NodesOutletContext } from './_dashboard.nodes'

export default function NodesPage() {
  const context = useOutletContext<NodesOutletContext>()
  return <Nodes createNodeRequest={context?.createNodeRequest} onCreateNodeConsumed={context?.consumeCreateNodeRequest} />
}
