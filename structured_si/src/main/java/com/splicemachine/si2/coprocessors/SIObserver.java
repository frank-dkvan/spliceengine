package com.splicemachine.si2.coprocessors;

import com.splicemachine.constants.TxnConstants;
import com.splicemachine.si.utils.SIUtils;
import com.splicemachine.si2.data.api.STable;
import com.splicemachine.si2.data.hbase.HGet;
import com.splicemachine.si2.data.hbase.HScan;
import com.splicemachine.si2.data.hbase.HbRegion;
import com.splicemachine.si2.filters.SIFilter;
import com.splicemachine.si2.si.api.TransactionId;
import com.splicemachine.si2.si.api.Transactor;
import com.splicemachine.si2.txn.TransactorFactoryImpl;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.BaseRegionObserver;
import org.apache.hadoop.hbase.coprocessor.ObserverContext;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.List;

public class SIObserver extends BaseRegionObserver {
    private static Logger LOG = Logger.getLogger(SIObserver.class);
    protected HRegion region;
    private boolean tableEnvMatch = false;
    private String tableName;

    @Override
    public void start(CoprocessorEnvironment e) throws IOException {
        SpliceLogUtils.trace(LOG, "starting %s", SIObserver.class);
        region = ((RegionCoprocessorEnvironment) e).getRegion();
        tableName = ((RegionCoprocessorEnvironment) e).getRegion().getTableDesc().getNameAsString();
        tableEnvMatch = (SIUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TxnConstants.TableEnv.USER_TABLE)
                || SIUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TxnConstants.TableEnv.USER_INDEX_TABLE)
                || SIUtils.getTableEnv((RegionCoprocessorEnvironment) e).equals(TxnConstants.TableEnv.DERBY_SYS_TABLE))
                && !tableName.equals(TxnConstants.TEMP_TABLE);
        super.start(e);
    }

    @Override
    public void stop(CoprocessorEnvironment e) throws IOException {
        SpliceLogUtils.trace(LOG, "stopping %s", SIObserver.class);
        super.stop(e);
    }

    @Override
    public void preGet(ObserverContext<RegionCoprocessorEnvironment> e, Get get, List<KeyValue> results) throws IOException {
        SpliceLogUtils.trace(LOG, "preGet %s", get);
        if (tableEnvMatch && shouldUseSI(new HGet(get))) {
            Transactor transactor = TransactorFactoryImpl.getTransactor();
            transactor.preProcessGet(new HGet(get));
            assert (get.getMaxVersions() == Integer.MAX_VALUE);
            addSiFilterToGet(e, get);
        }
        super.preGet(e, get, results);
    }

    private void logEvent(String event) {
        LOG.warn("SIObserver " + event + " " + tableEnvMatch + " " + "_" + " " + tableName + " " + Thread.currentThread().getName() + " " + Thread.currentThread().getId());
    }


    @Override
    public RegionScanner preScannerOpen(ObserverContext<RegionCoprocessorEnvironment> e, Scan scan, RegionScanner s) throws IOException {
        SpliceLogUtils.trace(LOG, "preScannerOpen %s", scan);
        if (tableEnvMatch && shouldUseSI(new HScan(scan))) {
            Transactor transactor = TransactorFactoryImpl.getTransactor();
            transactor.preProcessScan(new HScan(scan));
            assert (scan.getMaxVersions() == Integer.MAX_VALUE);
            addSiFilterToScan(e, scan);
        }
        return super.preScannerOpen(e, scan, s);
    }

    private boolean shouldUseSI(Object operation) {
        Transactor transactor = TransactorFactoryImpl.getTransactor();
        return transactor.isFilterNeeded(operation);
    }

    private void addSiFilterToGet(ObserverContext<RegionCoprocessorEnvironment> e, Get get) throws IOException {
        Transactor transactor = TransactorFactoryImpl.getTransactor();
        Filter newFilter = makeSiFilter(e, transactor.getTransactionIdFromGet(new HGet(get)), get.getFilter());
        get.setFilter(newFilter);
    }

    private void addSiFilterToScan(ObserverContext<RegionCoprocessorEnvironment> e, Scan scan) throws IOException {
        Transactor transactor = TransactorFactoryImpl.getTransactor();
        Filter newFilter = makeSiFilter(e, transactor.getTransactionIdFromScan(new HScan(scan)), scan.getFilter());
        scan.setFilter(newFilter);
    }

    private Filter makeSiFilter(ObserverContext<RegionCoprocessorEnvironment> e, TransactionId transactionId, Filter currentFilter) throws IOException {
        Transactor transactor = TransactorFactoryImpl.getTransactor();
        SIFilter siFilter = new SIFilter(transactor, transactionId, new HbRegion(e.getEnvironment().getRegion()));
        Filter newFilter;
        if (currentFilter != null) {
            newFilter = new FilterList(FilterList.Operator.MUST_PASS_ALL, currentFilter, siFilter); // Wrap Existing Filters
        } else {
            newFilter = siFilter;
        }
        return newFilter;
    }

    @Override
    public void prePut(ObserverContext<RegionCoprocessorEnvironment> e, Put put, WALEdit edit, boolean writeToWAL) throws IOException {
        if (tableEnvMatch) {
            Transactor transactor = TransactorFactoryImpl.getTransactor();
            STable region = new HbRegion(e.getEnvironment().getRegion());
            boolean processed = transactor.processPut(region, put);
            if (processed) {
                e.bypass();
                e.complete();
            }
        }
    }

    @Override
    public void preDelete(ObserverContext<RegionCoprocessorEnvironment> e, Delete delete, WALEdit edit,
                          boolean writeToWAL) throws IOException {
        if (tableEnvMatch) {
            throw new RuntimeException("Direct deletes are not supported under snapshot isolation. Instead a Put is expected that will set a record level tombstone.");
        } else {
            super.preDelete(e, delete, edit, writeToWAL);
        }
    }
}
