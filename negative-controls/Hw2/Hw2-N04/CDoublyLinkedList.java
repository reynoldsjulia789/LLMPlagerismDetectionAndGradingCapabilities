/*
 * Negative control for HW2 (AI-generated benchmark solution, not student work).
 * CDoublyLinkedList - circular, doubly linked, one sentinel node.
 * Built around two small primitives, insertBefore() and remove(Node), which all
 * mutating methods reuse. Searches walk backwards with a reverse index.
 */
public class CDoublyLinkedList {

	private class Node {
		private Object data;   //Assume data implemented Comparable
		private Node next, prev;
		private Node(Object data, Node pref, Node next)
		{
			this.data = data;
			this.prev = pref;
			this.next = next;
		}
	}

	private Node head;
	private int size;

	public CDoublyLinkedList() {
		this.head = new Node(null, null, null );
		this.head.next = this.head;
		this.head.prev=this.head;
		this.size = 0;
	}

	public boolean isEmpty() {
		return this.head == this.head.next;
	}

	// Add Object data to start of this LinkedList
	// Please DO NOT change this addFirst() method.
	// You must keep and include this provided addFirst() method
	//      in your source code.
	public void addFirst(Object data) {
		Node nn = new Node(data, this.head, this.head.next);
		this.head.next.prev = nn;
		this.head.next = nn;
		this.size ++;
	}

	// ---- primitives -------------------------------------------------------

	private Node insertBefore(Node anchor, Object data) {
		Node nn = new Node(data, anchor.prev, anchor);
		anchor.prev.next = nn;
		anchor.prev = nn;
		size++;
		return nn;
	}

	private Object remove(Node victim) {
		victim.prev.next = victim.next;
		victim.next.prev = victim.prev;
		size--;
		return victim.data;
	}

	private static boolean eq(Object a, Object b) {
		return (a == null && b == null) || (a != null && a.equals(b));
	}

	/** Last node holding o (searching prev links from the back), or null. */
	private Node findFromBack(Object o) {
		Node n = head.prev;
		while (n != head) {
			if (eq(n.data, o)) {
				return n;
			}
			n = n.prev;
		}
		return null;
	}

	// ---- required methods -------------------------------------------------

	public void addLast(Object data) {
		insertBefore(head, data); // the slot before the sentinel is the end
	}

	@SuppressWarnings("unchecked")
	public CDoublyLinkedList subListOfSmallerValues(Comparable data) {
		CDoublyLinkedList lesser = new CDoublyLinkedList();
		Node n = head.next;
		while (n != head) {
			Object v = n.data;
			n = n.next;
			if (v == null) {
				continue; // nulls are skipped
			}
			if (data.compareTo(v) > 0) {
				lesser.addLast(v);
			}
		}
		return lesser;
	}

	public boolean removeStartingAtBack(Object dataToRemove) {
		Node match = findFromBack(dataToRemove);
		if (match == null) {
			return false;
		}
		remove(match);
		return true;
	}

	public int lastIndexOf(Object o) {
		int reverseIndex = 0;
		for (Node n = head.prev; n != head; n = n.prev) {
			if (eq(n.data, o)) {
				return size - 1 - reverseIndex;
			}
			reverseIndex++;
		}
		return -1;
	}

	public boolean retainAll(CDoublyLinkedList other) throws NullPointerException {
		if (other == null) {
			throw new NullPointerException("retainAll: other is null");
		}
		int removedCount = 0;
		Node n = head.next;
		while (n != head) {
			Node nextNode = n.next;
			if (other.findFromBack(n.data) == null) {
				remove(n);
				removedCount++;
			}
			n = nextNode;
		}
		return removedCount > 0;
	}

	@SuppressWarnings("unchecked")
	private static boolean lessOrEqual(Object a, Object b) {
		if (a == null) {
			return true;
		}
		if (b == null) {
			return false;
		}
		return ((Comparable<Object>) a).compareTo(b) <= 0;
	}

	public void insertionSort() {
		Node boundary = head.next; // last node of the sorted prefix
		if (boundary == head) {
			return;
		}
		while (boundary.next != head) {
			Node candidate = boundary.next;
			if (lessOrEqual(boundary.data, candidate.data)) {
				boundary = candidate; // already in place, grow the prefix
				continue;
			}
			Object value = remove(candidate);
			Node anchor = boundary;
			while (anchor.prev != head && !lessOrEqual(anchor.prev.data, value)) {
				anchor = anchor.prev;
			}
			insertBefore(anchor, value);
		}
	}

	@Override
	public String toString() {
		String result = "{";
	    for (Node node = this.head.next; node != this.head; node = node.next) {
	    		if(node.next != this.head)
	    			result += node.data + "->";
	    		else
	    			result += node.data;
	    }
	    return result + "}";
	  }
}
