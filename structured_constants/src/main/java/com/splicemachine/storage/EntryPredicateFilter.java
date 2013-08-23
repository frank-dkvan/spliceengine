package com.splicemachine.storage;

import com.google.common.collect.Lists;
import com.splicemachine.constants.bytes.BytesUtil;
import com.splicemachine.encoding.MultiFieldDecoder;
import com.splicemachine.storage.index.BitIndex;
import com.splicemachine.utils.ByteDataInput;
import com.splicemachine.utils.ByteDataOutput;
import org.apache.hadoop.hbase.util.Pair;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author Scott Fines
 * Created on: 7/8/13
 */
public class EntryPredicateFilter implements Externalizable{
    public static EntryPredicateFilter EMPTY_PREDICATE = new EntryPredicateFilter(new BitSet(), Collections.<Predicate>emptyList());
    private static final long serialVersionUID = 3l;
    private BitSet fieldsToReturn;
    private List<Predicate> valuePredicates;
    private boolean returnIndex;
    private long visitedRowCount;
    private Map<Integer, List<Predicate>> predicateMap;


    public static EntryPredicateFilter emptyPredicate(){
        return EMPTY_PREDICATE;
    }

    /**
     * Used for Serialization, DO NOT USE
     */
    @Deprecated
    public EntryPredicateFilter(){}

    public EntryPredicateFilter(BitSet fieldsToReturn, List<Predicate> predicates){
        this.fieldsToReturn = fieldsToReturn;
        this.valuePredicates = Collections.unmodifiableList(new LinkedList<Predicate>(predicates));
        this.returnIndex=false;
        this.predicateMap = Collections.unmodifiableMap(PredicateUtils.initPredicateMap(predicates));
    }

    public EntryPredicateFilter(BitSet fieldsToReturn, List<Predicate> predicates,boolean returnIndex){
        this.fieldsToReturn = fieldsToReturn;
        this.valuePredicates = Collections.unmodifiableList(new LinkedList<Predicate>(predicates));
        this.returnIndex=returnIndex;
        this.predicateMap = Collections.unmodifiableMap(PredicateUtils.initPredicateMap(predicates));
    }

    public boolean match(EntryDecoder entry,EntryAccumulator accumulator) throws IOException {
        BitIndex encodedIndex = entry.getCurrentIndex();
        BitSet remainingFields = accumulator.getRemainingFields();

        MultiFieldDecoder decoder = entry.getEntryDecoder();
        for(int encodedPos =encodedIndex.nextSetBit(0);
            encodedPos>=0&&encodedPos<=remainingFields.length();
            encodedPos=encodedIndex.nextSetBit(encodedPos+1)){
            if(!remainingFields.get(encodedPos)){
                entry.seekForward(decoder, encodedPos);
                continue;
            }

            int offset = decoder.offset();

            byte[] array = decoder.array();
            if(offset>array.length){
                /*
                 * It may seem odd that this case can happen, but it occurs when the scan
                 * doesn't provide information about how many columns it expects back, and
                 * the bit index is an AllFullBitIndex (This is the case when you want to return
                 * everything, and everything is present). In that case, there is nothing to stop
                 * this from looping off the end of the data array (which, in fact, is the correct
                 * behavior when you want to return everything), so we have to check and make sure that
                 * there actually IS data to add before adding. In that case, we've finished, so
                 * tell the accumulator
                 */
                if(accumulator instanceof AlwaysAcceptEntryAccumulator)
                    ((AlwaysAcceptEntryAccumulator)accumulator).complete();
                return true;
            }
            entry.seekForward(decoder, encodedPos);


            int limit = decoder.offset()-1-offset;
            if(offset+limit>array.length){
                limit = array.length-offset;
            }

            List<Predicate> predicates = predicateMap.get(encodedPos);

            if(predicates != null){
                for(Predicate valuePredicate : predicates){
                    if(valuePredicate.applies(encodedPos) && !valuePredicate.match(encodedPos,array, offset,limit)){
                        return false;
                    }
                }
            }

            entry.accumulate(encodedPos, ByteBuffer.wrap(array, offset, limit), accumulator);
        }
        return true;
    }

    public BitSet getCheckedColumns(){
        BitSet predicateColumns = new BitSet();
        for(Predicate predicate:valuePredicates){
            predicate.setCheckedColumns(predicateColumns);
        }
        return predicateColumns;
    }

    public boolean checkPredicates(ByteBuffer buffer,int position){
        for(Predicate predicate:valuePredicates){
            if(predicate.checkAfter()){
                if(buffer!=null){
                    if(!predicate.match(position,buffer.array(),buffer.position(),buffer.remaining()))
                        return false;
                }else{
                    if(!predicate.match(position,null,0,0))
                        return false;
                }
            }
        }
        return true;
    }

    public void rowReturned(){
        visitedRowCount++;
    }

    public void reset(){
        for(Predicate predicate:valuePredicates){
            predicate.reset();
        }
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(fieldsToReturn);
        out.writeBoolean(returnIndex);
        out.writeInt(valuePredicates.size());
        for (Predicate valuePredicate : valuePredicates) {
            out.writeObject(valuePredicate);
        }
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        fieldsToReturn = (BitSet)in.readObject();
        returnIndex = in.readBoolean();
        int size = in.readInt();

        List<Predicate> preds = Lists.newArrayListWithCapacity(size);
        for(int i=0;i<size;i++){
            preds.add((Predicate)in.readObject());
        }

        this.valuePredicates = Collections.unmodifiableList(preds);
        this.predicateMap = Collections.unmodifiableMap(PredicateUtils.initPredicateMap(valuePredicates));
    }

    public EntryAccumulator newAccumulator(){
        if(fieldsToReturn.isEmpty())
            return new AlwaysAcceptEntryAccumulator(this,returnIndex);
        else
            return new SparseEntryAccumulator(this,fieldsToReturn,returnIndex);
    }

    public byte[] toBytes() {
        //if we dont have any distinguishing information, just send over an empty byte array
        if(fieldsToReturn.length()==0 && valuePredicates.size()<=0 && !returnIndex)
            return new byte[]{};

        /*
         * Format is as follows:
         * BitSet bytes
         * 1-byte returnIndex
         * n-bytes predicates
         */
        byte[] bitSetBytes = BytesUtil.toByteArray(fieldsToReturn);
        byte[] predicates = Predicates.toBytes(valuePredicates);
        byte[] finalData = new byte[predicates.length+bitSetBytes.length+1];
        System.arraycopy(bitSetBytes,0,finalData,0,bitSetBytes.length);
        finalData[bitSetBytes.length] = returnIndex? (byte)0x01: 0x00;
        System.arraycopy(predicates,0,finalData,bitSetBytes.length+1,predicates.length);
        return finalData;
//        ByteDataOutput bdo = new ByteDataOutput();
//        try{
//            bdo.writeObject(this);
//            return bdo.toByteArray();
//        }catch(IOException ioe){
//            throw new RuntimeException(ioe);
//        }
    }

    public List<Predicate> getPredicateList() {
        return valuePredicates;
    }

    public long getVisitedRowCount() {
        return visitedRowCount;
    }

    public static EntryPredicateFilter fromBytes(byte[] data) throws IOException {
        if(data==null||data.length==0) return EMPTY_PREDICATE;

        Pair<BitSet,Integer> fieldsToReturn = BytesUtil.fromByteArray(data,0);
        boolean returnIndex = data[fieldsToReturn.getSecond()] > 0;
        Pair<List<Predicate>,Integer> predicates = Predicates.allFromBytes(data,fieldsToReturn.getSecond()+1);

        return new EntryPredicateFilter(fieldsToReturn.getFirst(),predicates.getFirst(),returnIndex);
    }
}
