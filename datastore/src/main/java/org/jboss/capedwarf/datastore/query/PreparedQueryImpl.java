package org.jboss.capedwarf.datastore.query;

import java.util.Iterator;
import java.util.List;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.FetchOptions;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.QueryResultIterable;
import com.google.appengine.api.datastore.QueryResultIterator;
import com.google.appengine.api.datastore.QueryResultList;
import org.infinispan.query.CacheQuery;

import static com.google.appengine.api.datastore.FetchOptions.Builder.withDefaults;

/**
 * JBoss GAE PreparedQuery
 *
 * @author <a href="mailto:marko.luksa@gmail.com">Marko Luksa</a>
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class PreparedQueryImpl extends QueryHolder implements PreparedQuery {
    private final Query gaeQuery;
    private final CacheQuery cacheQuery;
    private final boolean inTx;

    public PreparedQueryImpl(Query gaeQuery, CacheQuery cacheQuery, boolean inTx) {
        this.gaeQuery = gaeQuery;
        this.cacheQuery = cacheQuery;
        this.inTx = inTx;
    }

    Query getQuery() {
        return gaeQuery;
    }

    CacheQuery getCacheQuery() {
        return cacheQuery;
    }

    boolean isInTx() {
        return inTx;
    }

    public List<Entity> asList(FetchOptions fetchOptions) {
        return asQueryResultList(fetchOptions);
    }

    @SuppressWarnings("unchecked")
    public QueryResultList<Entity> asQueryResultList(FetchOptions fetchOptions) {
        return new LazyQueryResultList<Entity>(this, fetchOptions);
    }

    public Iterable<Entity> asIterable() {
        return asIterable(withDefaults());
    }

    public Iterable<Entity> asIterable(FetchOptions fetchOptions) {
        return asQueryResultIterable(fetchOptions);
    }

    public QueryResultIterable<Entity> asQueryResultIterable() {
        return asQueryResultIterable(withDefaults());
    }

    public QueryResultIterable<Entity> asQueryResultIterable(FetchOptions fetchOptions) {
        return new LazyQueryResultIterable<Entity>(this, fetchOptions);
    }

    public Iterator<Entity> asIterator() {
        return asIterator(withDefaults());
    }

    public Iterator<Entity> asIterator(FetchOptions fetchOptions) {
        return asQueryResultIterator(fetchOptions);
    }

    public QueryResultIterator<Entity> asQueryResultIterator() {
        return asQueryResultIterator(withDefaults());
    }

    @SuppressWarnings("unchecked")
    public QueryResultIterator<Entity> asQueryResultIterator(FetchOptions fetchOptions) {
        return new LazyQueryResultIterator<Entity>(this, fetchOptions);
    }

    public Entity asSingleEntity() throws TooManyResultsException {
        Iterator<Entity> iterator = asIterator();
        Entity firstResult = iterator.hasNext() ? iterator.next() : null;
        if (iterator.hasNext()) {
            throw new TooManyResultsException();
        }
        return firstResult;
    }

    public int countEntities() {
        return countEntities(withDefaults());
    }

    public int countEntities(FetchOptions fetchOptions) {
        return new CountEntities(this, fetchOptions).count();
    }

    private static class CountEntities extends LazyChecker {
        private CountEntities(QueryHolder holder, FetchOptions fetchOptions) {
            super(holder, fetchOptions);
        }

        private int count() {
            check();
            apply();
            return holder.getCacheQuery().getResultSize();
        }
    }
}
