import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import org.apache.log4j.Logger;

/**
 * From the below implementation it is intended to be used to decouple execution
 * of a task entirely from the invoker. It is expected that there will not be
 * any return value or exception to be returned back to the caller. Create proxy
 * 
 * How to utilize this via wrapping it via Proxy
 * <pre>
 * ActionInterface proxy = (ActionInterface) Proxy.newProxyInstance(ActionInterface.class.getClassLoader(),
 * 		new Class[] { ActionInterface.class }, new DecoupleInvocation(new MyFooClass()));
 * </pre>
 * 
  *
 */
public class DecoupledInvocationHandler implements InvocationHandler {

	static final Logger logger = Logger.getLogger(DecoupledInvocationHandler.class);
	private final Object callHandler;

	
	/**
	 * Stop the worker thread
	 */
	public void stopWorker() {
		logger.info("DecouplingProxy shutting down Servant class"
				+ this.callHandler.getClass());
		this.executor.shutdown();
	}

	/**
	 * Delegate the invocation to the proxied object worker thread queue. The
	 * method will run asynchronously at some point in the future unless it
	 * returns a value - this exception is to allow for passing calls to utility
	 * methods like hashCode() or equals() rather than for active use (as
	 * exceptions will not be passed back for void return methods).
	 */
	@Override
	public Object invoke(final Object proxy, final Method method,
			final Object[] args) throws Throwable {
		if (this.executor.isShutdown()) {
			logger.error("The DecouplingProxy is already shutdown. Servant class"
					+ this.callHandler.getClass()
					+ "Request could not be processed.");
			return null;
		}
//		logger.info("Invoking "+method.getName());

		final MethodRequest methodRequest = new MethodRequest();
		methodRequest.method = method;
		methodRequest.arguments = args;
		if (method.getReturnType() != void.class) {
			// We don't want to delegate hashCode() and equals() to the target
			// as a common pattern is for a notifier to send a notification and
			// then remove the client from a notification collection which
			// requires the use of hashCode() or equals(), this can often lead
			// to deadlocks and is adequately handled by using the identity of
			// the proxy for this purpose instead.
			if (method.getName().equals("hashCode")) {
//				logger.info("Invoked "+method.getName());
				return this.hashCode();
			}
			if (method.getName().equals("equals")) {
//				logger.info("Invoked "+method.getName());
				return this.equals(methodRequest.arguments[0]);
			}
			final Future<Object> retval = this.executor.submit(methodRequest);
//			logger.info("Invoked "+method.getName());
			return retval.get();
		}

		try {
			this.executor.submit(methodRequest);
		} catch (final RejectedExecutionException e) {
			logger.info("Exception caught in Decoupling Proxy ", e);
		} catch (final NullPointerException e) {
			logger.info("Exception caught in Decoupling Proxy ", e);
		}
//		logger.info("Invoked "+method.getName());
		return null;
		
	}

	
	
	/**
	 * @return the servant
	 */
	public Object getServant() {
		return this.callHandler;
	}

	private final ExecutorService executor;

	/**
	 * @param callHandler
	 *            the underlying object to be made an ActiveObject
	 */
	public DecoupledInvocationHandler(final Object servant) {
		this(Executors.newSingleThreadExecutor(), servant);
	}
	
	private String getMessage(final Throwable t) {
		final String errorMsg = t.getClass().getSimpleName() + " : "
				+ ((t.getMessage() == null) ? "no message" : t.getMessage());
		return errorMsg;
	}

	/**
	 * @param name
	 *            the name of the thread associated with this ActiveObjectProxy
	 * @param callHandler
	 *            the underlying object to be made an ActiveObject
	 */
	public DecoupledInvocationHandler(final String name, final Object servant) {
		this(Executors.newSingleThreadExecutor(new NamedThreadFactory(name)),
				servant);
	}

	/**
	 * @param executor
	 *            an execution context for the active object
	 * @param callHandler
	 *            the underlying object to be made an ActiveObject
	 */
	public DecoupledInvocationHandler(final ExecutorService executor,
			final Object callHandler) {
		this.executor = executor;
		this.callHandler = callHandler;
	}


	/**
	 * Object embedding the Method request. (Used to construct Future)
	 */
	class MethodRequest implements Callable<Object> {
		/**
		 * Method object
		 */
		public Method method;
		/**
		 * arguments expected by the method
		 */
		public Object[] arguments;

		@Override
		public Object call() throws Exception {
			try {
				return this.method.invoke(getServant(), this.arguments);
			} catch (final Exception e) {
				final Throwable cause = e.getCause() == null ? e : e.getCause();
				// If method was void, the caller isn't waiting for this
				// exception so log and catch it to not cause trouble for the
				// Executor, otherwise the original caller will get it and
				// handle it
				if (this.method.getReturnType() == void.class) {
					logger.info("Exception caught in Decoupling Proxy: ", e);
					return null;
				} else {
					// if we got one of these and it contains an Exception
					// hierarchy exception, we can re-throw as the original type
					if (cause instanceof Exception) {
						throw (Exception) cause;
					}
					// Should never happen
					throw e;
				}
			} catch (final Throwable t) {
				logger.info("Unexpected Exception caught in Decoupling Proxy: ", t);

				throw new RuntimeException(t);
			}
		}
	}
}
