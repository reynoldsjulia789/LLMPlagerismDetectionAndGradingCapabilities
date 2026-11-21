/*
 * Negative control for HW1 (AI-generated benchmark solution, not student work).
 * MyLinkedList - a singly linked list that keeps a sentinel node in front.
 * Design: most index-based operations share one private helper, nodeBefore(),
 * and appends use an O(1) tail reference.
 */
import java.util.NoSuchElementException;
import java.util.Objects;

public class MyLinkedList {

	private ListNode head;
	private int size;
	private ListNode tail; // last real node, or head when the list is empty

	//inner class for ListNode
	private class ListNode {
		private Object data;
		private ListNode next;
		private ListNode(Object d) {
			this.data = d;
			this.next = null;
		}
	}

	public MyLinkedList() {
		this.head = new ListNode(null); //with a dummy head node
		this.size = 0;
		this.tail = this.head;
	}

	public int size() {
		return this.size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	// Add Object e to start of this LinkedList
	// Please DO NOT change this addFirst() method.
	// You must keep and include this provided addFirst() method in your source code.
	public void addFirst(Object e)
	{
		ListNode node = new ListNode(e);
		node.next = head.next;
		head.next = node;
		size++;
	}

	/** Returns the node at position index-1 (the sentinel for index 0). */
	private ListNode nodeBefore(int index) {
		ListNode n = head;
		for (int k = 0; k < index; k++) {
			n = n.next;
		}
		return n;
	}

	/** Unlinks the node after 'before' and returns its data. */
	private Object unlinkAfter(ListNode before) {
		ListNode gone = before.next;
		before.next = gone.next;
		size--;
		return gone.data;
	}

	/**
	 * The provided addFirst() may not be changed, so it cannot maintain tail. Operations that
	 * might invalidate tail reset it to head, and it is recomputed lazily here.
	 */
	private ListNode lastNode() {
		if (tail == head || tail.next != null) {
			tail = nodeBefore(size);
		}
		return tail;
	}

	private void checkElementIndex(int index) {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
	}

	public Object removeFirst() throws Exception {
		if (isEmpty()) {
			throw new Exception("Exception: LinkedList is empty!");
		}
		tail = head; // force recomputation on the next append
		return unlinkAfter(head);
	}

	public boolean contains(Object o) {
		return indexOf(o) >= 0;
	}

	private int indexOf(Object o) {
		int i = 0;
		for (ListNode n = head.next; n != null; n = n.next, i++) {
			if (Objects.equals(o, n.data)) {
				return i;
			}
		}
		return -1;
	}

	public boolean remove(Object o) {
		int at = indexOf(o);
		if (at < 0) {
			return false;
		}
		remove(at);
		return true;
	}

	public boolean removeAllCopies(Object o) {
		int before = size;
		ListNode scan = head;
		while (scan.next != null) {
			if (Objects.equals(o, scan.next.data)) {
				unlinkAfter(scan);
			} else {
				scan = scan.next;
			}
		}
		tail = head;
		return size != before;
	}

	public static MyLinkedList interleave(MyLinkedList A, MyLinkedList B) {
		MyLinkedList c = new MyLinkedList();
		int longest = Math.max(A.size, B.size);
		ListNode x = A.head.next;
		ListNode y = B.head.next;
		for (int k = 0; k < longest; k++) {
			if (x != null) {
				c.add(x.data);
				x = x.next;
			}
			if (y != null) {
				c.add(y.data);
				y = y.next;
			}
		}
		return c;
	}

	public void add(int index, Object o) {
		if (index < 0 || index > this.size) {
			throw new IndexOutOfBoundsException("Index Passed in not valid!");
		}
		ListNode before = nodeBefore(index);
		ListNode fresh = new ListNode(o);
		fresh.next = before.next;
		before.next = fresh;
		size++;
		tail = head;
	}

	public Object get(int index) throws IndexOutOfBoundsException {
		checkElementIndex(index);
		return nodeBefore(index).next.data;
	}

	public Object remove(int index) throws IndexOutOfBoundsException {
		checkElementIndex(index);
		tail = head;
		return unlinkAfter(nodeBefore(index));
	}

	public boolean add(Object e) {
		ListNode last = lastNode();
		last.next = new ListNode(e);
		tail = last.next;
		size++;
		return true;
	}

        //Please DO NOT Change the following toString() method!!!
        //You must include the following toString() method in your source code.
	@Override
	public String toString() {
		String result = "{";
	    for (ListNode node = this.head.next; node != null; node = node.next) {
	    		if(node.next != null)
	    			result += node.data + "->";
	    		else
	    			result += node.data;
	    }
	    return result + "}";
	  }
}
