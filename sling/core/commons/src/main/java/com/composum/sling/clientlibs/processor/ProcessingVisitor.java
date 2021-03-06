package com.composum.sling.clientlibs.processor;

import com.composum.sling.clientlibs.handle.*;
import com.composum.sling.clientlibs.service.ClientlibService;
import com.composum.sling.clientlibs.service.ClientlibProcessor;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;

import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.slf4j.LoggerFactory.getLogger;
import static com.composum.sling.clientlibs.handle.ClientlibVisitor.VisitorMode.*;

/**
 * Appends all embedded files to an input stream.
 */
public class ProcessingVisitor extends AbstractClientlibVisitor {

    private static final Logger LOG = getLogger(ProcessingVisitor.class);

    protected final OutputStream output;
    protected final ClientlibProcessor processor;
    protected final ProcessorContext context;

    /**
     * Instantiates a new Processing visitor.
     *
     * @param service   the service
     * @param output    the output stream to write to. Is not closed in this class - remember to close it outside.
     * @param processor optional processor we pipe our output through.
     * @param context   the context where we keep some data
     */
    public ProcessingVisitor(ClientlibElement owner, ClientlibService service, OutputStream output,
                             ClientlibProcessor processor, ProcessorContext context) {
        super(owner, service, context.getResolver(), null);
        this.output = output;
        this.processor = processor;
        this.context = context;
    }

    @Override
    protected ClientlibVisitor createVisitorFor(ClientlibElement element) {
        return new ExcludeDependenciesVisitor(element, service, resolver, processedElements);
    }

    @Override
    public void action(ClientlibFile clientlibFile, VisitorMode mode, ClientlibResourceFolder parent) throws RepositoryException, IOException {
        if (EMBEDDED != mode) return;
        Resource resource = clientlibFile.handle.getResource();
        if (context.useMinifiedFiles()) {
            resource = service.getMinifiedSibling(resource);
        }
        FileHandle file = new FileHandle(resource);
        InputStream content = file.getStream();
        if (content != null) {
            try {
                if (processor != null) {
                    content = processor.processContent(content, context);
                }
                IOUtils.copy(content, output);
                output.write('\n');
                output.write('\n');
                output.flush();
            } finally {
                content.close();
            }
        } else {
            logNotAvailable(resource, "[content]", parent.getOptional());
        }
    }

    protected void logNotAvailable(Resource resource, String reference, boolean optional) {
        if (optional)
            LOG.debug("Clientlib entry ''{}'' of ''{}'' not available but optional.", reference, resource.getPath());
        else LOG.warn("Clientlib entry ''{}'' of ''{}'' not available.", reference, resource.getPath());
    }

    /** Warns about everything that should be embedded, but is already processed, and not in this */
    @Override
    protected void alreadyProcessed(ClientlibRef ref, VisitorMode mode, ClientlibResourceFolder folder) {
        if (mode == EMBEDDED) {
            LOG.warn("Trying to embed already embedded / dependency {} again at {}", ref, folder);
        }
    }

    /**
     * If some files are included in / requested by dependencies of the rendered client library, these must not be
     * included into the cached file, since these would be loaded twice by the page. Thus, all dependencies are
     * processed by this visitor. We need a separate visitor as to note embedded stuff in client libraries that are
     * dependencies of the processed library has to be noted as well, and this cannot easily be distinguished by the
     * visit methods arguments.
     * <p>
     * This visitor is necessary since the dependencies have to be processed as well since they might override
     * file versions or exclude stuff from embedding.
     */
    protected static class ExcludeDependenciesVisitor extends AbstractClientlibVisitor {

        protected ExcludeDependenciesVisitor(ClientlibElement owner, ClientlibService service, ResourceResolver resolver, LinkedHashSet<ClientlibLink> processedElements) {
            super(owner, service, resolver, processedElements);
        }

        @Override
        protected ClientlibVisitor createVisitorFor(ClientlibElement element) {
            return new ExcludeDependenciesVisitor(element, service, resolver, processedElements);
        }

    }
}
