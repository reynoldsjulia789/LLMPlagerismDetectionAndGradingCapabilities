/*
 * Negative control for HW2 (AI-generated benchmark solution, not student work).
 * CDoublyLinkedList: circular doubly linked list with one dummy node.
 * Insertion sort relinks nodes (it does not copy data between nodes).
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

	private static boolean equal(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}

	public void addLast(Object data) {
		Node last = head.prev;
		Node nn = new Node(data, last, head);
		last.next = nn;
		head.prev = nn;
		size++;
	}

	@SuppressWarnings("unchecked")
	public CDoublyLinkedList subListOfSmallerValues(Comparable data) {
		CDoublyLinkedList smaller = new CDoublyLinkedList();
		for (Node n = head.next; n != head; n = n.next) {
			if (n.data != null && data.compareTo(n.data) > 0) {
				smaller.addLast(n.data);
			}
		}
		return smaller;
	}

	public boolean removeStartingAtBack(Object dataToRemove) {
		for (Node n = head.prev; n != head; n = n.prev) {
			if (equal(dataToRemove, n.data)) {
				n.prev.next = n.next;
				n.next.prev = n.prev;
				size--;
				return true;
			}
		}
		return false;
	}

	public int lastIndexOf(Object o) {
		int index = size - 1;
		for (Node n = head.prev; n != head; n = n.prev, index--) {
			if (equal(o, n.data)) {
				return index;
			}
		}
		return -1;
	}

	private boolean contains(Object o) {
		for (Node n = head.next; n != head; n = n.next) {
			if (equal(o, n.data)) {
				return true;
			}
		}
		return false;
	}

	public boolean retainAll(CDoublyLinkedList other) throws NullPointerException {
		if (other == null) {
			throw new NullPointerException("other list is null");
		}
		boolean changed = false;
		Node n = head.next;
		while (n != head) {
			Node following = n.next;
			if (!other.contains(n.data)) {
				n.prev.next = n.next;
				n.next.prev = n.prev;
				size--;
				changed = true;
			}
			n = following;
		}
		return changed;
	}

	// nulls sort before everything else
	@SuppressWarnings("unchecked")
	private static int compare(Object a, Object b) {
		if (a == null || b == null) {
			return a == null ? (b == null ? 0 : -1) : 1;
		}
		return ((Comparable<Object>) a).compareTo(b);
	}

	public void insertionSort() {
		if (size < 2) {
			return;
		}
		Node unsorted = head.next.next; // first node is a sorted run of length one
		while (unsorted != head) {
			Node nextToInsert = unsorted.next;
			Node spot = unsorted.prev;
			while (spot != head && compare(spot.data, unsorted.data) > 0) {
				spot = spot.prev;
			}
			if (spot != unsorted.prev) {
				// unlink and re-insert after spot
				unsorted.prev.next = unsorted.next;
				unsorted.next.prev = unsorted.prev;
				unsorted.next = spot.next;
				unsorted.prev = spot;
				spot.next.prev = unsorted;
				spot.next = unsorted;
			}
			unsorted = nextToInsert;
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
