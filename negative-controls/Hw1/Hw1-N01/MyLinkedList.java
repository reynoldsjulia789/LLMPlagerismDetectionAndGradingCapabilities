/*
 * Negative control for HW1 (AI-generated benchmark solution, not student work).
 * MyLinkedList: singly linked list with a dummy head node.
 * Style: classic previous/current pointer walks for every operation.
 */
import java.util.NoSuchElementException;

public class MyLinkedList {

	private ListNode head;
	private int size;

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

	// null-safe equality used by the search/remove methods
	private static boolean same(Object a, Object b) {
		if (a == null) {
			return b == null;
		}
		return a.equals(b);
	}

	public Object removeFirst() throws Exception {
		if (size == 0) {
			throw new Exception("Exception: LinkedList is empty!");
		}
		ListNode first = head.next;
		head.next = first.next;
		size--;
		return first.data;
	}

	public boolean contains(Object o) {
		ListNode current = head.next;
		while (current != null) {
			if (same(o, current.data)) {
				return true;
			}
			current = current.next;
		}
		return false;
	}

	public boolean remove(Object o) {
		ListNode previous = head;
		ListNode current = head.next;
		while (current != null) {
			if (same(o, current.data)) {
				previous.next = current.next;
				size--;
				return true;
			}
			previous = current;
			current = current.next;
		}
		return false;
	}

	public boolean removeAllCopies(Object o) {
		boolean removedAny = false;
		ListNode previous = head;
		// previous only advances when the next node is kept, so adjacent copies are handled
		while (previous.next != null) {
			if (same(o, previous.next.data)) {
				previous.next = previous.next.next;
				size--;
				removedAny = true;
			} else {
				previous = previous.next;
			}
		}
		return removedAny;
	}

	public static MyLinkedList interleave(MyLinkedList A, MyLinkedList B) {
		MyLinkedList result = new MyLinkedList();
		ListNode a = A.head.next;
		ListNode b = B.head.next;
		while (a != null || b != null) {
			if (a != null) {
				result.add(a.data);
				a = a.next;
			}
			if (b != null) {
				result.add(b.data);
				b = b.next;
			}
		}
		return result;
	}

	public void add(int index, Object o) {
		if (index < 0 || index > size) {
			throw new IndexOutOfBoundsException("Index Passed in not valid!");
		}
		ListNode previous = head;
		for (int i = 0; i < index; i++) {
			previous = previous.next;
		}
		ListNode node = new ListNode(o);
		node.next = previous.next;
		previous.next = node;
		size++;
	}

	public Object get(int index) throws IndexOutOfBoundsException {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
		ListNode current = head.next;
		for (int i = 0; i < index; i++) {
			current = current.next;
		}
		return current.data;
	}

	public Object remove(int index) throws IndexOutOfBoundsException {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
		ListNode previous = head;
		for (int i = 0; i < index; i++) {
			previous = previous.next;
		}
		ListNode target = previous.next;
		previous.next = target.next;
		size--;
		return target.data;
	}

	public boolean add(Object e) {
		ListNode last = head;
		while (last.next != null) {
			last = last.next;
		}
		last.next = new ListNode(e);
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
