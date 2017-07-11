package com.composum.sling.clientlibs.handle.com.composum.sling.core.concurrent;

import com.composum.sling.core.ResourceHandle;
import com.composum.sling.core.concurrent.LazyCreationService;
import com.composum.sling.core.concurrent.LazyCreationServiceImpl;
import com.composum.sling.core.concurrent.SemaphoreSequencer;
import com.composum.sling.core.concurrent.SequencerService;
import org.apache.commons.collections.Factory;
import org.apache.commons.collections.map.DefaultedMap;
import org.apache.commons.collections.map.LazyMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.lock.LockManager;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.composum.sling.core.util.ResourceUtil.*;
import static org.apache.sling.hamcrest.ResourceMatchers.props;
import static org.junit.Assert.*;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Tests for {@link com.composum.sling.core.concurrent.LazyCreationServiceImpl}.
 */
public class TestLazyCreationServiceImpl {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private static final Logger LOG = getLogger(TestLazyCreationServiceImpl.class);

    protected LazyCreationService lazyCreationService;

    protected ResourceResolverFactory resourceResolverFactory;
    protected ExecutorService executor;
    protected Random rnd;
    protected Map<String, AtomicInteger> initCount = Collections.synchronizedMap(LazyMap.decorate(new TreeMap<>(),
            new Factory() {
                @Override
                public Object create() {
                    return new AtomicInteger();
                }
            }));


    protected static final Map<String, Object> FOLDER_PROPS = new HashMap<String, Object>() {{
        put(PROP_PRIMARY_TYPE, TYPE_SLING_FOLDER);
    }};

    protected static final Map<String, Object> ITEM_PROPS = new HashMap<String, Object>() {{
        put(PROP_PRIMARY_TYPE, TYPE_UNSTRUCTURED);
    }};

    protected static final SequencerService<SequencerService.Token> NOSEQUENCER = new SequencerService<SequencerService
            .Token>() {
        @Override
        public Token acquire(String key) {
            return null;
        }

        @Override
        public void release(Token token) {
        }
    };

    protected final List<String> paths = Arrays.asList("/var/t1/c1/a", "/var/t1/c1/b", "/var/t1/c1/c", "/var/t1/c2/e",
            "/var/t1/c2/f", "/var/t2/c3/g", "/var/t2/c3/h", "/var/t2/c3/i", "/var/t2/c4/j", "/var/t2/c4/k",
            "/var/t2/c4/l", "/var/t2/c4/m", "/var/t2/c4/n", "/var/t2/c5/o", "/var/t2/c6/p", "/var/t2/c7/q",
            "/var/t2/c8/r");

    protected void setup(final SequencerService sequencerService) {
        rnd = new Random();
        long seed = rnd.nextLong();
        LOG.info("Seed: {}", seed);
        rnd.setSeed(seed);
        Collections.shuffle(paths, rnd);
        resourceResolverFactory = context.getService(ResourceResolverFactory.class);
        lazyCreationService = new LazyCreationServiceImpl() {{
            sequencer = sequencerService;
            resolverFactory = resourceResolverFactory;
            maximumLockWaitTimeSec = 2;
        }};
        executor = Executors.newFixedThreadPool(20);
    }

    @After
    public void teardown() {
        if (null != executor) assertTrue(executor.shutdownNow().isEmpty());
    }

    /**
     * Check that nothing was initialized twice. Put initCount.clear() in the test to avoid that.
     * does often not work since incomprehensibly two threads lock the same thing.
     */
    @After
    public void checkNothingWasInitializedTwice() {
        for (Map.Entry<String, AtomicInteger> entry : initCount.entrySet()) {
            assertEquals("" + initCount, 1, entry.getValue().get());
        }
    }

    @Test
    public void testCreation() throws Exception {
        setup(new SemaphoreSequencer() {{
            activate(context.componentContext());
        }});
        runCreationInParallel(0);
    }

    /** Simulate collisions in cluster as far as possible - SequencerService doesn't work there. */
    @Test
    public void testCreationWithoutSequencer() throws Exception {
        setup(NOSEQUENCER);
        runCreationInParallel(0);
    }

    @Test
    public void testCreationWithRandomDelays() throws Exception {
        setup(NOSEQUENCER);
        runCreationInParallel(100);
    }

    protected void runCreationInParallel(final int delay) throws Exception {
        final int numGetOrCreates = 40;
        List<Future<ResourceHandle>> futures = new ArrayList<Future<ResourceHandle>>();
        for (int i = 0; i < numGetOrCreates; ++i) {
            final String path = paths.get(rnd.nextInt(paths.size()));
            final ResourceResolver resolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
            futures.add(executor.submit(new Callable<ResourceHandle>() {
                public ResourceHandle call() throws Exception {
                    ResourceHandle handle = lazyCreationService.getOrCreate(resolver, path, makeGetter(),
                            makeCreator(delay), makeParentCreator(delay));
                    return handle;
                }
            }));
        }
        for (Future<ResourceHandle> future : futures) {
            ResourceHandle handle = future.get();
            validateResult(handle, false);
        }
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/"));
    }

    @Test
    public void testSimpleCreateAndInit() throws Exception {
        final SequencerService sequencerService = new SemaphoreSequencer() {{
            activate(context.componentContext());
        }};
        setup(sequencerService);
        ResourceHandle result = lazyCreationService.getOrCreate(context.resourceResolver(), "/var/s/a/b/c",
                makeGetter(), makeCreator(0), makeInitializer(0), makeParentCreator(0));
        validateResult(result, true);
    }

    protected void runCreationAndInitInParallel(final int delay) throws Exception {
        final int numGetOrCreates = 40;
        List<Future<ResourceHandle>> futures = new ArrayList<Future<ResourceHandle>>();
        for (int i = 0; i < numGetOrCreates; ++i) {
            final String path = paths.get(rnd.nextInt(paths.size()));
            final ResourceResolver resolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
            futures.add(executor.submit(new Callable<ResourceHandle>() {
                public ResourceHandle call() throws Exception {
                    ResourceHandle handle = lazyCreationService.getOrCreate(resolver, path,
                            makeGetter(), makeCreator(delay), makeInitializer(delay), makeParentCreator(delay));
                    return handle;
                }
            }));
        }
        for (Future<ResourceHandle> future : futures) {
            ResourceHandle handle = future.get();
            validateResult(handle, true);
        }
        // JcrTestUtils.printResourceRecursivelyAsJson(context.resourceResolver().getResource("/"));
    }

    @Test
    public void testCreationAndInit() throws Exception {
        setup(new SemaphoreSequencer() {{
            activate(context.componentContext());
        }});
        runCreationAndInitInParallel(0);
    }

    /** Stresstest: simulate collisions in cluster as far as possible - SequencerService doesn't work there. */
    @Test
    public void testCreationAndInitWithoutSequencer() throws Exception {
        setup(NOSEQUENCER);
        runCreationAndInitInParallel(0);
        // switch off checking that nothing is initialized twice since that doesn't work because of a bug in the
        // JCR locking: two threads trying to lock the same thing get a lock
        initCount.clear();
    }

    @Test
    public void testCreationAndInitWithRandomDelays() throws Exception {
        setup(NOSEQUENCER);
        runCreationAndInitInParallel(100);
    }

    /**
     * Runs a call that is fast to create the resource, but is slow to initialize it, and a second call that is slow
     * to create the resource (is overrun by the first) but then finishes the job.
     */
    @Test
    public void testCreationAndInitWithLockBreak() throws Exception {
        setup(NOSEQUENCER);
        final String path = context.uniqueRoot().content() + "/lock/break";
        long begin = System.currentTimeMillis();
        Future<ResourceHandle> future1 = executor.submit(new Callable<ResourceHandle>() {
            public ResourceHandle call() throws Exception {
                final ResourceResolver resolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
                ResourceHandle handle = lazyCreationService.getOrCreate(resolver, path,
                        makeGetter(), makeCreator(0), makeInitializer(4000), makeParentCreator(0));
                return handle;
            }
        });
        Future<ResourceHandle> future2 = executor.submit(new Callable<ResourceHandle>() {
            public ResourceHandle call() throws Exception {
                final ResourceResolver resolver2 = resourceResolverFactory.getAdministrativeResourceResolver(null);
                ResourceHandle handle = lazyCreationService.getOrCreate(resolver2, path,
                        makeGetter(), makeCreator(100), makeInitializer(300), makeParentCreator(20));
                return handle;
            }
        });
        validateResult(future2.get(), true);
        long timing = System.currentTimeMillis() - begin;
        assertTrue("Did this wait for lock? " + timing, timing > 1500);
        assertTrue("Lock should have been broken. " + timing, timing < 2500);
        validateResult(future1.get(), true);
        timing = System.currentTimeMillis() - begin;
        assertTrue("" + timing, timing >= 3000);
        initCount.clear(); // avoid the check in teardown since it doesn't fit here.
    }

    /** Delay by a random time between 3/4 and 5/4 * delay milliseconds. */
    protected void randomlyDelay(int delay) {
        try {
            if (0 < delay) Thread.sleep(rnd.nextInt(delay / 2) + 3 * delay / 4);
        } catch (InterruptedException e) {
            fail("Impossible: " + e);
        }
    }

    protected LazyCreationService.RetrievalStrategy<ResourceHandle> makeGetter() {
        return new LazyCreationService.RetrievalStrategy<ResourceHandle>() {
            @Override
            public ResourceHandle get(ResourceResolver resolver, String path) throws
                    RepositoryException {
                ResourceHandle handle1 = ResourceHandle.use(resolver.getResource(path));
                return handle1.isValid() ? handle1 : null;
            }
        };
    }

    protected LazyCreationService.ParentCreationStrategy makeParentCreator(final int delay) {
        return new LazyCreationService.ParentCreationStrategy() {
            @Override
            public Resource createParent(ResourceResolver resolver, Resource parentsParent, String parentName, int
                    level) throws RepositoryException, PersistenceException {
                randomlyDelay(delay);
                return resolver.create(parentsParent, parentName, FOLDER_PROPS);
            }
        };
    }

    protected LazyCreationService.CreationStrategy makeCreator(final int delay) {
        return new LazyCreationService.CreationStrategy() {
            @Override
            public Resource create(ResourceResolver resolver, Resource parent, String name)
                    throws RepositoryException, PersistenceException {
                randomlyDelay(delay);
                return resolver.create(parent, name, ITEM_PROPS);
            }
        };
    }

    protected LazyCreationService.InitializationStrategy makeInitializer(final int delay) {
        return new LazyCreationService.InitializationStrategy() {
            @Override
            public void initialize(ResourceResolver resolver, Resource resource) throws RepositoryException,
                    PersistenceException {
                randomlyDelay(delay);
                initCount.get(resource.getPath()).incrementAndGet();
                ResourceHandle.use(resource).setProperty("initialized", Calendar.getInstance());
            }
        };
    }

    protected void validateResult(ResourceHandle result, boolean initialized) throws RepositoryException {
        assertTrue(result.getPath(), result.isValid());
        if (initialized) {
            assertNotNull(result.getPath(), result.getProperty("initialized"));
            assertNotNull(result.getPath(), result.getProperty(PROP_LAST_MODIFIED));
        }
        assertEquals(TYPE_UNSTRUCTURED, result.getPrimaryType());
        assertEquals(TYPE_SLING_FOLDER, result.getParent().getPrimaryType());
        LockManager lockManager = context.resourceResolver().adaptTo(Session.class).getWorkspace().getLockManager();
        assertFalse("Initialized=" + initialized + " but still locked: " + result.getPath(), lockManager.holdsLock
                (result.getPath()));
        if (result.getResourceResolver() != context.resourceResolver()) result.getResourceResolver().close();
    }

    /** Parallel writing to a resource fails only when the written properties differ in value. */
    @Test
    public void testParallelWrite() throws Exception {
        setup(NOSEQUENCER);
        final String path = context.uniqueRoot().content() + "/parallelwrite";
        final ResourceHandle handle = lazyCreationService.getOrCreate(context.resourceResolver(), path,
                makeGetter(), makeCreator(0), makeInitializer(0), makeParentCreator(0));
        final ResourceResolver resolver = resourceResolverFactory.getAdministrativeResourceResolver(null);
        final ResourceResolver resolver2 = resourceResolverFactory.getAdministrativeResourceResolver(null);
        Future<ResourceHandle> future1 = executor.submit(new Callable<ResourceHandle>() {
            public ResourceHandle call() throws Exception {
                ResourceHandle.use(resolver.getResource(path)).setProperty("test", "ha");
                ResourceHandle.use(resolver.getResource(path)).setProperty("test1", "hu");
                Thread.sleep(500);
                resolver.commit();
                return handle;
            }
        });
        Future<ResourceHandle> future2 = executor.submit(new Callable<ResourceHandle>() {
            public ResourceHandle call() throws Exception {
                ResourceHandle.use(resolver2.getResource(path)).setProperty("test", "ha");
                ResourceHandle.use(resolver.getResource(path)).setProperty("test2", "ho");
                Thread.sleep(500);
                resolver2.commit();
                return handle;
            }
        });
        future1.get();
        future2.get();
    }

}