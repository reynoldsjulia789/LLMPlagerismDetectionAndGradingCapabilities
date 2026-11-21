/*
 * Negative control for HW1 (AI-generated benchmark solution, not student work).
 * MyLinkedList with a dummy head; this version solves the search and removal
 * operations recursively on the node chain instead of with loops.
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

	private boolean matches(Object target, ListNode node) {
		return target == null ? node.data == null : target.equals(node.data);
	}

	public Object removeFirst() throws Exception {
		if (head.next == null) {
			throw new Exception("Exception: LinkedList is empty!");
		}
		Object value = head.next.data;
		head.next = head.next.next;
		size -= 1;
		return value;
	}

	public boolean contains(Object o) {
		return containsFrom(head.next, o);
	}

	private boolean containsFrom(ListNode node, Object o) {
		if (node == null) {
			return false;
		}
		return matches(o, node) || containsFrom(node.next, o);
	}

	public boolean remove(Object o) {
		return removeAfter(head, o);
	}

	// removes the first match found after 'node'; recursion stops at the first hit
	private boolean removeAfter(ListNode node, Object o) {
		if (node.next == null) {
			return false;
		}
		if (matches(o, node.next)) {
			node.next = node.next.next;
			size -= 1;
			return true;
		}
		return removeAfter(node.next, o);
	}

	public boolean removeAllCopies(Object o) {
		int sizeBefore = size;
		head.next = stripped(head.next, o);
		return size < sizeBefore;
	}

	// returns the chain starting at node with every match removed
	private ListNode stripped(ListNode node, Object o) {
		if (node == null) {
			return null;
		}
		ListNode rest = stripped(node.next, o);
		if (matches(o, node)) {
			size -= 1;
			return rest;
		}
		node.next = rest;
		return node;
	}

	public static MyLinkedList interleave(MyLinkedList A, MyLinkedList B) {
		MyLinkedList merged = new MyLinkedList();
		weave(merged, A.head.next, B.head.next);
		return merged;
	}

	// takes one item from 'first', then continues with the lists swapped
	private static void weave(MyLinkedList out, MyLinkedList.ListNode first, MyLinkedList.ListNode second) {
		if (first == null && second == null) {
			return;
		}
		if (first == null) {
			weave(out, second, null);
			return;
		}
		out.add(first.data);
		weave(out, second, first.next);
	}

	public void add(int index, Object o) {
		if (index > size || index < 0) {
			throw new IndexOutOfBoundsException("Index Passed in not valid!");
		}
		ListNode spot = head;
		int steps = index;
		while (steps-- > 0) {
			spot = spot.next;
		}
		ListNode inserted = new ListNode(o);
		inserted.next = spot.next;
		spot.next = inserted;
		size += 1;
	}

	public Object get(int index) throws IndexOutOfBoundsException {
		if (index >= size || index < 0) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
		return nodeAt(head.next, index).data;
	}

	private ListNode nodeAt(ListNode node, int index) {
		return index == 0 ? node : nodeAt(node.next, index - 1);
	}

	public Object remove(int index) throws IndexOutOfBoundsException {
		if (index >= size || index < 0) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
		ListNode before = index == 0 ? head : nodeAt(head.next, index - 1);
		Object value = before.next.data;
		before.next = before.next.next;
		size -= 1;
		return value;
	}

	public boolean add(Object e) {
		add(size, e);
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
