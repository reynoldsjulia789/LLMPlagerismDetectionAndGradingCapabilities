/*
 * Negative control for HW2 (AI-generated benchmark solution, not student work).
 * Circular doubly linked list (dummy node). In this version the insertion sort
 * keeps the nodes in place and shifts data values, like the array version of
 * the algorithm, and removal goes through one shared unlink() helper.
 */
import java.util.Objects;

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

	/** Removes node x from the ring. */
	private void unlink(Node x) {
		x.prev.next = x.next;
		x.next.prev = x.prev;
		x.next = x.prev = null;
		this.size--;
	}

	public void addLast(Object data) {
		Node created = new Node(data, this.head.prev, this.head);
		this.head.prev.next = created;
		this.head.prev = created;
		this.size++;
	}

	@SuppressWarnings("unchecked")
	public CDoublyLinkedList subListOfSmallerValues(Comparable data) {
		CDoublyLinkedList result = new CDoublyLinkedList();
		Node cursor = this.head.next;
		while (cursor != this.head) {
			Object value = cursor.data;
			if (value != null && ((Comparable<Object>) value).compareTo(data) < 0) {
				result.addLast(value);
			}
			cursor = cursor.next;
		}
		return result;
	}

	public boolean removeStartingAtBack(Object dataToRemove) {
		Node cursor = this.head.prev;
		while (cursor != this.head && !Objects.equals(cursor.data, dataToRemove)) {
			cursor = cursor.prev;
		}
		if (cursor == this.head) {
			return false;
		}
		unlink(cursor);
		return true;
	}

	public int lastIndexOf(Object o) {
		int found = -1;
		int i = 0;
		for (Node cursor = this.head.next; cursor != this.head; cursor = cursor.next) {
			if (Objects.equals(cursor.data, o)) {
				found = i; // keep going; the last match wins
			}
			i++;
		}
		return found;
	}

	public boolean retainAll(CDoublyLinkedList other) throws NullPointerException {
		Objects.requireNonNull(other, "other");
		int sizeBefore = this.size;
		Node cursor = this.head.next;
		while (cursor != this.head) {
			Node after = cursor.next;
			if (other.lastIndexOf(cursor.data) == -1) {
				unlink(cursor);
			}
			cursor = after;
		}
		return this.size != sizeBefore;
	}

	@SuppressWarnings("unchecked")
	private static boolean greater(Object a, Object b) {
		if (a == null) {
			return false;
		}
		if (b == null) {
			return true;
		}
		return ((Comparable<Object>) a).compareTo(b) > 0;
	}

	public void insertionSort() {
		// for each node, shift larger values one node to the right, then drop the key in
		for (Node current = this.head.next.next; current != this.head && current != this.head.next; current = current.next) {
			Object key = current.data;
			Node hole = current;
			while (hole.prev != this.head && greater(hole.prev.data, key)) {
				hole.data = hole.prev.data;
				hole = hole.prev;
			}
			hole.data = key;
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
