package krangl

import krangl.ArrayUtils.handleArrayErasure
import krangl.ArrayUtils.handleListErasure

/**
 * Spread a key-value pair across multiple columns.
 *
 * @param key The bare (unquoted) name of the column whose values will be used as column headings.
 * @param value The bare (unquoted) name of the column whose values will populate the cells.
 * @param fill If set, missing values will be replaced with this value.
 * @param convert If set, attempt to do a type conversion will be run on all new columns. This is useful if the value column
 *                was a mix of variables that was coerced to a string.
 */
fun DataFrame.spread(key: String, value: String, fill: Any? = null, convert: Boolean = false): DataFrame {

    // create new columns
    val newColNames = this[key].values().distinct()  // .map { it.toString() } dont'convert already because otherwise join will fail

    // make sure that new column names do not exist already
    require(names.intersect(newColNames).isEmpty()) { "spread columns do already exist in data-frame" }


    val bySpreadGroup = groupBy(*names.minus(listOf(key, value)).toTypedArray()) as GroupedDataFrame

    // todo use big initially empty array here and fill it with spread data
    val spreadGroups: List<DataFrame> = bySpreadGroup
        .groups
        .map {
            val grpDf = it.df

            require(grpDf.select(key).distinct(key).nrow == grpDf.nrow) { "key value mapping is not unique" }

            val spreadBlock = SimpleDataFrame(handleListErasure(key, newColNames)).leftJoin(grpDf.select(key, value))

            val grpSpread = SimpleDataFrame((spreadBlock as SimpleDataFrame).rows.map {
                AnyCol(it[key].toString(), listOf(it[value]))
            })

            bindCols(grpDf.remove(key, value).distinct(), grpSpread)
        }

    //    if(fill!=null){
    //        spreadBlock =  spreadBlock.
    //    }

    val spreadWithGHashes = spreadGroups.bindRows()


    // coerce types of stringified columns similar to how tidy is doing things
    var typeCoercedSpread = newColNames.map { it.toString() }
        .foldRight(spreadWithGHashes, { spreadCol, df ->
            df.addColumn(spreadCol) { handleArrayErasure(spreadCol, df[spreadCol].values()) }
        })

    if (convert) {
        typeCoercedSpread = newColNames
            // stringify spread column names
            .map { it.toString() }

            // select for String-type coluymns
            .filter { typeCoercedSpread[it] is StringCol }

            // attempt conversion
            .foldRight(typeCoercedSpread, { spreadCol, df ->
                convertType(df, spreadCol)
            })

    }

    if (fill != null) {
        TODO()
    }

    return typeCoercedSpread
}


/** Gather takes multiple columns and collapses into key-value pairs, duplicating all other columns as needed. You use
 * gather() when you notice that you have columns that are not variables.
 *
 * @param key Name of the key column to create in output.
 * @param value Name of the value column to create in output.
 * @param columns The colums to gather. The same selectar syntax as for `krangl::select` is supported here
 * @param convert If TRUE will automatically run `convertType` on the key column. This is useful if the
 *                column names are actually numeric, integer, or logical.
 */
fun DataFrame.gather(key: String, value: String, columns: List<String> = this.names, convert: Boolean = false): DataFrame {
    require(columns.isNotEmpty()) { "the column selection to be `gather`ed must not be empty" }

    val gatherColumns = select(columns)

    // 1) convert each gather column into a block
    val distinctCols = gatherColumns.cols.map { it.javaClass }.distinct()


    // Why cant we use handleArrayErasure() here?
    // Because it would require iterating over data without any need since we know the type already
    @Suppress("UNCHECKED_CAST")
    fun makeValueCol(name: String, data: Array<*>): DataCol = when {
        distinctCols.size == 1 && distinctCols.first() == IntCol::class.java -> IntCol(name, data as Array<Int?>)
        distinctCols.size == 1 && distinctCols.first() == LongCol::class.java -> LongCol(name, data as Array<Long?>)
        distinctCols.size == 1 && distinctCols.first() == DoubleCol::class.java -> DoubleCol(name, data as Array<Double?>)
        distinctCols.size == 1 && distinctCols.first() == StringCol::class.java -> StringCol(name, data as Array<String?>)
        distinctCols.size == 1 && distinctCols.first() == BooleanCol::class.java -> BooleanCol(name, data as Array<Boolean?>)

    // upcast mixed gatherings including int, long and double
        setOf(IntCol::class.java, LongCol::class.java, DoubleCol::class.java) == distinctCols.toSet() -> DoubleCol(name, data.map { (it as Number?)?.toDouble() })

    // fall back to use any column
        else -> AnyCol(name, data as Array<Any?>)
    }

    val gatherBlock = gatherColumns.cols.map { column ->
        SimpleDataFrame(
            StringCol(key, Array(column.length, { column.name as String? })),
            makeValueCol(value, column.values())
        )
    }.bindRows().let {
        // optionally try to convert key column
        if (convert) convertType(it, key) else it
    }


    // 2) row-replicate the non-gathered columns
    //    val rest = select(names.minus(gatherColumns.names))
    val rest = remove(gatherColumns.names)
    //    val replicationLevel = gatherColumns.ncol

    val indexReplication = rest.cols.map { column ->
        handleArrayErasure(column.name, Array(gatherBlock.nrow, { index -> column.values()[index.rem(column.length)] }))
    }.run { SimpleDataFrame(this) }


    // 3) combined the gather-block with the replicated index-data
    return bindCols(indexReplication, gatherBlock)
}


fun DataFrame.gather(key: String, value: String,
                     columns: ColumnSelector, // no default here to avoid signature clash = { all() },
                     convert: Boolean = false
): DataFrame = gather(key, value, colSelectAsNames(columns), convert)


/**
 * Convert a character vector to logical, integer, numeric, complex or factor as appropriate.
 *
 * @see tidyr::type.convert
 */
internal fun convertType(df: DataFrame, spreadColName: String): DataFrame {
    val spreadCol = df[spreadColName]
    val convColumn: DataCol = convertType(spreadCol)

    return df.addColumn(spreadColName) { convColumn }
}

internal fun convertType(spreadCol: DataCol): DataCol {
    val columnData = spreadCol.asStrings()
    val firstElements = columnData.take(20).toList()

    val convColumn: DataCol = when {
        isIntCol(firstElements) -> IntCol(spreadCol.name, columnData.map { it?.toInt() })
        isLongCol(firstElements) -> LongCol(spreadCol.name, columnData.map { it?.toLong() })
        isDoubleCol(firstElements) -> DoubleCol(spreadCol.name, columnData.map { it?.toDouble() })
        isBoolCol(firstElements) -> BooleanCol(spreadCol.name, columnData.map { it?.toBoolean() })
        else -> spreadCol
    }
    return convColumn
}

/**
 * Convenience function to paste together multiple columns into one.

 * @param colName Name of the column to add
 * @param sep Separator to use between values.
 * @param remove If TRUE, remove input columns from output data frame.
 *
 * @see @separate
 *
 */
fun DataFrame.unite(colName: String, which: List<String>, sep: String = "_", remove: Boolean = true): DataFrame {
    require(which.isNotEmpty()) { "the column selection to be `unite`ed must not be empty" }

    val uniteBlock = select(which)
    val uniteResult = uniteBlock.rows.map { it.values.map { it?.toString().nullAsNA() }.joinToString(sep) }

    val rest = if (remove) remove(uniteBlock.names) else this

    return rest.addColumn(colName) { uniteResult }
}


fun DataFrame.unite(colName: String, vararg which: ColNames.() -> List<Boolean?>, sep: String = "_", remove: Boolean = true): DataFrame =
    unite(colName, which = colSelectAsNames(reduceColSelectors(which)), sep = sep, remove = remove)


/**
 * Given either regular expression or a vector of character positions, separate() turns a single character column into multiple columns.
 *
 * @param column Bare column name.
 * @param into Names of new variables to create as character vector.
 * @param sep Separator between columns. If character, is interpreted as a regular expression. The default value is a regular @param expression that matches any sequence of non-alphanumeric values.
 * @param remove If TRUE, remove input column from output data frame.
 * @param convert If set, attempt to do a type conversion will be run on all new columns. This                  is useful if the value column was a mix of variables that was coerced to a string.
 */
fun DataFrame.separate(column: String, into: List<String>, sep: String = "[^\\w]", remove: Boolean = true, convert: Boolean = false): DataFrame {

    val sepCol = this[column]

    // split colum  by given delimter and keep NAs
    val splitData = sepCol.asStrings().map { it?.split(sep.toRegex()) }
    val splitWidths = splitData.map { it?.size }.filterNotNull().distinct()
    val numSplits = splitWidths.first()

    require(splitWidths.size == 1) { "unequal splits are not yet supported" }
    require(numSplits == into.size) { "mismatch between number of splits ${numSplits} and provided new column names '${into}'" }

    // vertically split into columns and perform optional type conversion
    val splitCols: List<DataCol> = (0..(numSplits - 1)).map { splitIndex ->
        StringCol(into[splitIndex], splitData.map { it?.get(splitIndex) })
    }.map {
        // optionally do type conversion
        if (convert) convertType(it) else it
    }

    // column bind rest and separated columns into final result
    val rest = if (remove) remove(column) else this

    return bindCols(rest, SimpleDataFrame(splitCols))
}


const val DEF_NEST_COLUMN_NAME = "data"

/**
 * Nest repeated values in a list-variable.
 *
 * There are many possible ways one could choose to nest colSelect inside a data frame. nest() creates a list of data
 * frames containing all the nested variables: this seems to be the most useful form in practice.
 *
 * Usage
 *
 * ```
 * nest(data, ..., columnName = "data")
 * ```
 *
 * @param colSelect A selection of colSelect. If not provided, all except the grouping variables are selected. You can
 * supply bare variable names, select all variables between x and z with x:z, exclude y with -y. For more options, see the `select()` documentation.
 *
 * @param columnName The name of the new column, as a string or symbol.
 */
// also see https://github.com/tidyverse/tidyr/blob/master/R/nest.R
fun DataFrame.nest(
    colSelect: ColumnSelector = { except(*groupedBy().names.toTypedArray()) },
    columnName: String = DEF_NEST_COLUMN_NAME
): DataFrame {
    val nestColumns = colSelectAsNames(colSelect)

    return when {

        this is GroupedDataFrame -> {
            require(nestColumns.intersect(by).isEmpty()) { "Can not nest grouping columns" }

            val listColumn = groups().map { it.select { listOf(nestColumns) } }
            groupedBy().addColumn(columnName) { listColumn }.ungroup()
        }

        nestColumns.size == names.size -> { // are all columns nested away
            dataFrameOf(columnName)(this)
        }

        else -> {
            groupBy { except(*nestColumns.toTypedArray()) }.nest(colSelect)
        }
    }
}

/**
 * If you have a list-column, this makes each element of the list its own row. It unfolds data vertically. unnest() can handle list-columns that can atomic vectors, lists, or data frames (but not a mixture of the different types).
 *
 * To unfold properties of objects (that is unfolding data horizontally), use `unfold()`
 */
fun DataFrame.unnest(columnName: String): DataFrame {
    val firstOrNull = get(columnName).values().asSequence().filterNotNull().firstOrNull()

    // if the list column is a list we repackage into a single column data-frame column with the same name
    if (firstOrNull !is DataFrame) {
        val repackaged = when (firstOrNull) {
            is Iterable<*> -> addColumn(columnName) { it[columnName].map<List<*>> { values -> dataFrameOf(columnName)(*values.toTypedArray()) } }
            else -> throw IllegalArgumentException("Unnesting failed because of unsupported list column type")
        }

        return repackaged.unnest(columnName)
    }

    val dataCol = get(columnName).asType<DataFrame>()

    val replicationIndex = dataCol
        .mapIndexed { rowNumber, dataFrame -> IntArray(dataFrame?.nrow ?: 1, { rowNumber }) }
        .flatMap { it.toList() }


    val left = replicateByIndex(remove(columnName), replicationIndex)

    val unnested = dataCol.toList()
        .map { it ?: emptyDataFrame() }
        .bindRows()

    return bindCols(left, unnested)

}


// see https://rdrr.io/cran/tidyr/man/complete.html
/**
 * Turns implicit missing values into explicit missing values. This is a wrapper around expand(), dplyr::left_join()
 * and replace_na() that's useful for completing missing combinations of data.
 */
// todo add fill argument as in tidyr::complete
fun DataFrame.complete(vararg columnNames: String): DataFrame =
    expand(*columnNames).leftJoin(this, by = columnNames.asIterable())

/**
 * expand() is often useful in conjunction with left_join if you want to convert implicit missing values to explicit
 * missing values. Or you can use it in conjunction with anti_join() to figure out which combinations are missing.
 */
fun DataFrame.expand(vararg columnNames: String): DataFrame {
    val dummyCol = tempColumnName()
    val folded = columnNames
        .map { select(it).distinct() }
        .fold(dataFrameOf(dummyCol)(1)) { acc, next ->
            cartesianProductWithoutBy(acc, next, byColumns = listOf(dummyCol))
        }

    return folded.remove(dummyCol).sortedBy(*columnNames)
}
