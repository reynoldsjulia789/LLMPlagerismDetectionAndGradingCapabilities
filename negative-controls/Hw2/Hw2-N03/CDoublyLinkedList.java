/*
 * Negative control for HW2 (AI-generated benchmark solution, not student work).
 * Circular doubly linked list with a dummy node.
 * insertionSort() detaches the whole chain and rebuilds it by inserting each
 * detached node into an initially empty sorted ring; retainAll() builds the
 * list of survivors and swaps it in.
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

	public void addLast(Object data) {
		Node tailNode = new Node(data, null, null);
		tailNode.prev = head.prev;
		tailNode.next = head;
		head.prev.next = tailNode;
		head.prev = tailNode;
		size += 1;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public CDoublyLinkedList subListOfSmallerValues(Comparable data) {
		CDoublyLinkedList out = new CDoublyLinkedList();
		Node walker = head.next;
		while (walker != head) {
			if (walker.data != null) {
				Comparable candidate = (Comparable) walker.data;
				if (candidate.compareTo(data) < 0) {
					out.addLast(walker.data);
				}
			}
			walker = walker.next;
		}
		return out;
	}

	private boolean same(Object x, Object y) {
		if (x == y) {
			return true;
		}
		return x != null && x.equals(y);
	}

	public boolean removeStartingAtBack(Object dataToRemove) {
		Node walker = head.prev;
		while (walker != head) {
			if (same(walker.data, dataToRemove)) {
				Node before = walker.prev;
				Node after = walker.next;
				before.next = after;
				after.prev = before;
				size -= 1;
				return true;
			}
			walker = walker.prev;
		}
		return false;
	}

	public int lastIndexOf(Object o) {
		Node walker = head.prev;
		int position = size;
		while (walker != head) {
			position -= 1;
			if (same(walker.data, o)) {
				return position;
			}
			walker = walker.prev;
		}
		return -1;
	}

	public boolean retainAll(CDoublyLinkedList other) throws NullPointerException {
		if (other == null) {
			throw new NullPointerException();
		}
		CDoublyLinkedList kept = new CDoublyLinkedList();
		for (Node walker = head.next; walker != head; walker = walker.next) {
			boolean inOther = false;
			for (Node o = other.head.next; o != other.head && !inOther; o = o.next) {
				inOther = same(walker.data, o.data);
			}
			if (inOther) {
				kept.addLast(walker.data);
			}
		}
		boolean removedSomething = kept.size != size;
		head = kept.head;
		size = kept.size;
		return removedSomething;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int order(Object x, Object y) {
		if (x == null) {
			return y == null ? 0 : -1;
		}
		if (y == null) {
			return 1;
		}
		return ((Comparable) x).compareTo(y);
	}

	public void insertionSort() {
		Node pending = head.next;
		head.next = head; // the ring is now an empty sorted list
		head.prev = head;
		while (pending != head) {
			Node node = pending;
			pending = pending.next;
			// find the first sorted node that is bigger, scanning from the back
			Node after = head;
			Node scan = head.prev;
			while (scan != head && order(scan.data, node.data) > 0) {
				after = scan;
				scan = scan.prev;
			}
			node.prev = after.prev;
			node.next = after;
			after.prev.next = node;
			after.prev = node;
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
