import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class Hw1ListAdapter
{
    private final Object   _object;
    private final Class<?> _class;

    private static final String[] CANDIDATE_CLASSES = {
            "MyLinkedList", "LinkedList", "SinglyLinkedList"
    };

    public Hw1ListAdapter()
    {
        Class<?> found = null;
        for (String name : CANDIDATE_CLASSES)
        {
            try
            {
                found = Class.forName(name);
                break;
            }
            catch (ClassNotFoundException ignored) {}
        }

        if (found == null)
        {
            throw new RuntimeException("No valid List class found in submission.");
        }

        this._class = found;

        try
        {
            this._object = _class.getDeclaredConstructor().newInstance();
        }
        catch (Exception caught)
        {
            throw new RuntimeException("Failed to instantiate " + _class.getName(), caught);
        }
    }

    private Hw1ListAdapter(Object existingInstance)
    {
        if (existingInstance == null)
        {
            throw new RuntimeException("Returned list instance was null.");
        }

        this._object = existingInstance;
        this._class  = existingInstance.getClass();
    }

    private Method findMethod(String name, Class<?>... paramTypes)
    {
        for (Method m : _class.getMethods())
        {
            if (m.getName().equalsIgnoreCase(name) && Arrays.equals(m.getParameterTypes(), paramTypes))
            {
                return m;
            }
        }

        throw new RuntimeException("Method '" + name + "' with matching parameters not found in " + _class.getName());
    }

    private Object invoke(String name, Class<?>[] paramTypes, Object... args) throws Throwable
    {
        try
        {
            Method m = findMethod(name, paramTypes);
            return m.invoke(_object, args);
        }
        catch (InvocationTargetException caught)
        {
            throw caught.getCause();
        }
    }

    private Object invokeUnchecked(String name, Class<?>[] paramTypes, Object... args)
    {
        try
        {
            return invoke(name, paramTypes, args);
        }
        catch (RuntimeException | Error caught)
        {
            throw caught;
        }
        catch (Throwable caught)
        {
            throw new RuntimeException(caught);
        }
    }

    public int size()
    {
        return (int) invokeUnchecked("size", new Class<?>[]{});
    }

    public boolean isEmpty()
    {
        return (boolean) invokeUnchecked("isEmpty", new Class<?>[]{});
    }

    public void addFirst(Object e)
    {
        invokeUnchecked("addFirst", new Class<?>[]{Object.class}, e);
    }

    public Object removeFirst() throws Exception
    {
        try
        {
            return invoke("removeFirst", new Class<?>[]{});
        }
        catch (Exception caught)
        {
            throw caught;
        }
        catch (Throwable caught)
        {
            throw new RuntimeException(caught);
        }
    }

    public boolean contains(Object o)
    {
        return (boolean) invokeUnchecked("contains", new Class<?>[]{Object.class}, o);
    }

    public boolean remove(Object o)
    {
        return (boolean) invokeUnchecked("remove", new Class<?>[]{Object.class}, o);
    }

    public boolean removeAllCopies(Object o)
    {
        return (boolean) invokeUnchecked("removeAllCopies", new Class<?>[]{Object.class}, o);
    }

    public static Hw1ListAdapter interleave(Hw1ListAdapter a, Hw1ListAdapter b)
    {
        try
        {
            Class<?> targetClass = a._class;
            Method m = a.findMethod("interleave", targetClass, targetClass);

            Object result = m.invoke(null, a._object, b._object);

            return new Hw1ListAdapter(result);
        }
        catch (InvocationTargetException caught)
        {
            Throwable cause = caught.getCause();

            if (cause instanceof RuntimeException) throw (RuntimeException) cause;

            throw new RuntimeException(cause);
        }
        catch (Exception caught)
        {
            throw new RuntimeException("Failed to invoke static interleave()", caught);
        }
    }

    public void add(int index, Object o)
    {
        invokeUnchecked("add", new Class<?>[]{int.class, Object.class}, index, o);
    }

    public Object get(int index)
    {
        return invokeUnchecked("get", new Class<?>[]{int.class}, index);
    }

    public Object remove(int index)
    {
        return invokeUnchecked("remove", new Class<?>[]{int.class}, index);
    }

    public boolean add(Object e)
    {
        return (boolean) invokeUnchecked("add", new Class<?>[]{Object.class}, e);
    }

    @Override
    public String toString()
    {
        return _object.toString();
    }
}

