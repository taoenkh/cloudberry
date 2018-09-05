
package edu.uci.ics.cloudberry.zion.model.impl

import edu.uci.ics.cloudberry.zion.model.datastore.{IQLGenerator, IQLGeneratorFactory, QueryParsingException}
import edu.uci.ics.cloudberry.zion.model.schema._
import org.joda.time.DateTime
import play.api.libs.json._

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

class OracleGenerator extends SQLGenerator {

  protected val quote = '"'
  protected val truncate: String = "truncate"
  protected val fullTextMatch = Seq("contains", "")

  //converts a value in internal geometry format to its plain text representation, e.g.: "POINT(1, 2)"
  private val geoAsText: String = "st_astext"
  //X/Y-coordinate value for the Point object in MySQL.
  private val pointGetCoord = Seq("st_x", "st_y")

  override protected def genDDL(name: String, schema: Schema): String = {

    def mkNestDDL(names: String, typeStr: String): String = {
      names match {
        case e => s"  $quote$e$quote $typeStr"
      }
    }

    val fields = schema.fieldMap.values.filter(f => f.dataType != DataType.Hierarchy && f != AllField).map {
      f => mkNestDDL(f.name, fieldType2SQLType(f) + (if (f.isOptional) " default null" else " not null"))
    }

    s"""
       |declare
       |    result1 number(8);
       | begin
       |    select count(*) into result1 from dba_tables where owner = 'BERRY' and table_name = '${name}';
       | if result1 = 0 then
       |
       |execute immediate 'create table $quote${name}$quote (
       |${fields.mkString(",\n")}, primary key (${schema.primaryKey.map(key => key.name).mkString(s"$quote",s"$quote,$quote",s"$quote")})
       |)';
       |end if;
       |end;
       |/\n""".stripMargin

  }

  override protected def parseSelect(selectOpt: Option[SelectStatement],
                                     exprMap: Map[String, FieldExpr], query: Query,
                                     queryBuilder: StringBuilder): ParsedResult = {
    selectOpt match {
      case Some(select) =>
        val producedExprs = mutable.LinkedHashMap.newBuilder[String, FieldExpr]

        val orderStrs = select.orderOn.zip(select.order).map {
          case (orderOn, order) =>
            val expr = exprMap(orderOn.name).defExpr
            val orderStr = if (order == SortOrder.DSC) "desc" else ""
            s"${expr} $orderStr"
        }

        val orderStr =
          if (orderStrs.nonEmpty) {
            orderStrs.mkString("order by ", ",", "")
          } else {
            s""
          }

        val limitStr = s"fetch first ${select.limit} rows only"
        appendIfNotEmpty(queryBuilder, orderStr)
        if (select.limit != 0) {
          appendIfNotEmpty(queryBuilder, limitStr)
        }

        if (select.fields.isEmpty || query.hasUnnest || query.hasGroup) {
          producedExprs ++= exprMap
        } else {
          select.fields.foreach {
            field => producedExprs += field.name -> exprMap(field.name)
          }
        }

        val newExprMap = producedExprs.result().toMap
        val projectStr = if (select.fields.isEmpty) {
          if (query.hasUnnest || query.hasGroup) {
            parseProject(exprMap)
          } else {
            s"select *"
          }
        } else {
          parseProject(producedExprs.result().toMap)
        }
        queryBuilder.insert(0, projectStr + "\n")
        ParsedResult(Seq.empty, newExprMap)

      case None =>
        val projectStr =
          if (query.hasUnnest || query.hasGroup) {
            parseProject(exprMap)
          } else {
            s"select *"
          }
        queryBuilder.insert(0, projectStr + "\n")
        ParsedResult(Seq.empty, exprMap)
    }
  }

  override def parseAppend(append: AppendView, schemaMap: Map[String, AbstractSchema]): String ={
    val (temporalSchemaMap, lookupSchemaMap) = GeneratorUtil.splitSchemaMap(schemaMap)
    val sourceSchema = temporalSchemaMap(append.query.dataset)
    val primaryKeystr:Seq[String] = for (pk<- sourceSchema.primaryKey)yield{
      pk.name
    }
    val measurementStrd:Seq[String] = for (d <- sourceSchema.measurement) yield {
      "d.\""+d.name+"\""
    }
    val measurementStrs:Seq[String] = for (d <- sourceSchema.measurement) yield {
      "s.\""+d.name+"\""
    }

    val dimensionStrd:Seq[String] = for (d<- sourceSchema.dimension)yield{
      "d.\""+d.name+"\""
    }
    val dimensionStrs:Seq[String] = for (d<- sourceSchema.dimension)yield{
      "s.\""+d.name+"\""
    }
    var conflictsting = ""
    for (pkname<- primaryKeystr){
      conflictsting += "d.\""+pkname+"\" = s.\"" + pkname+"\" and "
    }

    val insert = s"""
                    |merge into $quote${append.dataset}$quote d
                    |using (${parseQuery(append.query,schemaMap)}) s
                    |on (${conflictsting.dropRight(5)})
                    |when not matched then
                    |insert (${(dimensionStrd ++ measurementStrd).mkString(",")})
                    |values (${(dimensionStrs ++ measurementStrs).mkString(",")})
                    |""".stripMargin
    insert

  }


  override protected def parseGroupby(groupOpt: Option[GroupStatement],
                                      exprMap: Map[String, FieldExpr],
                                      queryBuilder: StringBuilder): ParsedResult = {
    groupOpt match {
      case Some(group) =>
        val producedExprs = mutable.LinkedHashMap.newBuilder[String, FieldExpr]
        val groupStrs = group.bys.map { by =>

          val fieldExpr = exprMap(by.field.name)
          val as = by.as.getOrElse(by.field)
          val groupExpr = parseGroupByFunc(by, fieldExpr.refExpr)
          val newExpr = s"$quote${as.name}$quote"
          producedExprs += (as.name -> FieldExpr(newExpr, groupExpr))
          s"$groupExpr"
        }

        val groupStr = s"group by ${groupStrs.mkString(",")}"

        appendIfNotEmpty(queryBuilder, groupStr)

        group.aggregates.foreach { aggr =>
          val fieldExpr = exprMap(aggr.field.name)
          val aggrExpr = parseAggregateFunc(aggr, fieldExpr.refExpr)
          val newExpr = s"$quote${aggr.as.name}$quote"
          producedExprs += aggr.as.name -> FieldExpr(newExpr, aggrExpr)
        }

        if (!group.lookups.isEmpty) {
          val producedExprMap = producedExprs.result().toMap
          val newExprMap =
            producedExprMap.map {
              case (field, expr) => field -> FieldExpr(s"$groupedLookupSourceVar.$quote$field$quote", s"$groupedLookupSourceVar.$quote$field$quote")
            }
          queryBuilder.insert(0, s"from (\n${parseProject(producedExprMap)}\n")
          queryBuilder.append(s"\n) $groupedLookupSourceVar\n")
          val resultAfterLookup = parseLookup(group.lookups, newExprMap, queryBuilder, true)
          ParsedResult(Seq.empty, resultAfterLookup.exprMap)
        } else {
          ParsedResult(Seq.empty, producedExprs.result().toMap)
        }

      case None => ParsedResult(Seq(""), exprMap)
    }
  }

  protected def parseCreate(create: CreateView, schemaMap: Map[String, AbstractSchema]): String = {

    val (temporalSchemaMap, lookupSchemaMap) = GeneratorUtil.splitSchemaMap(schemaMap)
    val sourceSchema = temporalSchemaMap(create.query.dataset)
    val resultSchema = calcResultSchema(create.query, sourceSchema)
    val ddl: String = genDDL(create.dataset, sourceSchema)
    val primaryKeystr:Seq[String] = for (pk<- sourceSchema.primaryKey)yield{
      pk.name
    }
    val measurementStrd:Seq[String] = for (d <- sourceSchema.measurement) yield {
      "d.\""+d.name+"\""
    }
    val measurementStrs:Seq[String] = for (d <- sourceSchema.measurement) yield {
      "s.\""+d.name+"\""
    }

    val dimensionStrd:Seq[String] = for (d<- sourceSchema.dimension)yield{
      "d.\""+d.name+"\""
    }
    val dimensionStrs:Seq[String] = for (d<- sourceSchema.dimension)yield{
      "s.\""+d.name+"\""
    }
    var conflictsting = ""
    for (pkname<- primaryKeystr){
      conflictsting += "d.\""+pkname+"\" = s.\"" + pkname+"\" and "
    }

    val insert = s"""
                    |merge into $quote${create.dataset}$quote d
                    |using (${parseQuery(create.query,schemaMap)}) s
                    |on (${conflictsting.dropRight(5)})
                    |when not matched then
                    |insert (${(dimensionStrd ++ measurementStrd).mkString(",")})
                    |values (${(dimensionStrs ++ measurementStrs).mkString(",")})
                    |""".stripMargin



    System.out.println("ddl + insert="+ddl+insert)
    ddl + insert


  }

  /**
    * Convert middleware datatype to OracleSQL datatype
    * @param field
    */
  protected def fieldType2SQLType(field: Field): String = {
    field.dataType match {
      case DataType.Number => "NUMBER"
      case DataType.Time => "TIMESTAMP"
      case DataType.Point => "VARCHAR2(255)"
      case DataType.Boolean => "NUMBER(1)"
      case DataType.String => "VARCHAR2(255)"
      case DataType.Text => "VARCHAR(1000)"
      case DataType.Bag => ???
      case DataType.Hierarchy => ???
      case DataType.Record => ???
    }
  }

  protected def parseUpsertMeta(q: UpsertRecord): String = {

    val records = q.records.value
    var queryResult = ArrayBuffer.empty[String]
    records.foreach {
      record =>
        val name: String = (record \ "name").as[JsString].value
        val schema: JsValue = (record \ "schema").as[JsValue]
        val dataInterval: JsValue = (record \ "dataInterval").as[JsValue]
        val stats: JsValue = (record \ "stats").as[JsValue]
        val createTime: String = TimeField.TimeFormatForSQL.print(new DateTime((record \ "stats" \ "createTime").as[String])).split("\\.")(0)
        println("createtime",createTime)
        //queryResult += (s"('${name}','${schema}','${dataInterval}','${stats}',to_date('${createTime}','yyyy-MM-dd hh24:mi:ss'))")
        queryResult += s"'${name}'"
        queryResult += s"'${schema}'"
        queryResult += s"'${dataInterval}'"
        queryResult += s"'${stats}'"
        queryResult += s"to_date('${createTime}','yyyy-MM-dd hh24:mi:ss')"


        println(queryResult,"QUery result")
    }
    s"""
       |merge into $quote${q.dataset}$quote d
       |using (select ${queryResult(0)} as "name", 2 as "schema" from dual ) s
       |on (d."name" = s."name")
       |when not matched then
       |insert (${quote}name${quote},${quote}schema${quote},${quote}dataInterval${quote},${quote}stats${quote},${quote}stats.createTime$quote)
       |values (${queryResult(0)},${queryResult(1)},${queryResult(2)},${queryResult(3)},${queryResult(4)})
       |when matched then
       |update
       |set
       |  "schema" = ${queryResult(1)},
       |  "dataInterval" = ${queryResult(2)},
       |  "stats" = ${queryResult(3)},
       |  "stats.createTime" = ${queryResult(4)}

       |""".stripMargin

  }

  protected def initExprMap(dataset: String, schemaMap: Map[String, AbstractSchema]): Map[String, FieldExpr] = {
    val schema = schemaMap(dataset)
    schema.fieldMap.mapValues {
      f => FieldExpr(s"$sourceVar.$quote${f.name}$quote", s"$sourceVar.$quote${f.name}$quote")
    }
  }

  protected def parseTextRelation(filter: FilterStatement, fieldExpr: String): String = {
    val wordsArr = ArrayBuffer[String]()
    filter.values.foreach(w => wordsArr += w.toString)
    val sb = new StringBuilder(s"${fullTextMatch(0)}($fieldExpr, '")
    sb.append(wordsArr.mkString(" and ") + s"',1)>0")
    sb.toString()
    //sample output
    // contains(name,'test',1)>0 equivalent to match(name) against ('+test' in boolean mode);
    // contains(name,'foo and bar',1)>0   equivalent to match(name) against ('+foo +bar' in boolean mode);
    // contains(name,'foo and bar and test',1 ) > 0   equivalent to match(name) against ('+foo + bar +test' in boolean mode);
  }


  override protected def parseTimeRelation(filter: FilterStatement,
                                           fieldExpr: String): String = {



    filter.relation match {
      case Relation.inRange => {
        filter.field.dataType match{
          case time: DataType.Time.type =>
            s"$fieldExpr >= to_date('${TimeField.TimeFormatForSQL.print(new DateTime(filter.values(0).toString)).split("\\.")(0)}','yyyy-MM-dd hh24:mi:ss') and $fieldExpr < to_date('${TimeField.TimeFormatForSQL.print(new DateTime(filter.values(1).toString)).split("\\.")(0)}','yyyy-MM-dd hh24:mi:ss')"
          case others =>
            s"$fieldExpr >= '${filter.values(0)}' and $fieldExpr < '${filter.values(1)}'"
        }
      }
      case _ => {
        filter.field.dataType match {
          case time: DataType.Time.type =>
            s"$fieldExpr ${filter.relation} to_date('${TimeField.TimeFormatForSQL.print(new DateTime(filter.values(0).toString)).split("\\.")(0)}','yyyy-mm-dd hh24:mi:ss')"
          case _ =>
            s"$fieldExpr ${filter.relation} '${filter.values(0)}'"
        }
      }
    }
  }

  //TODO: unnest
  protected def parseUnnest(unnest: Seq[UnnestStatement],
                            exprMap: Map[String, FieldExpr], queryBuilder: StringBuilder): ParsedResult = {
    //return the empty result & exprMap for next step's process.
    ParsedResult((new ListBuffer[String]), exprMap)
  }
  override protected def timeUnitFuncMap(unit: TimeUnit.Value): String = unit match {
    case TimeUnit.Second => "second"
    case TimeUnit.Minute => "minute"
    case TimeUnit.Hour => "hour"
    case TimeUnit.Day => "date"
    case TimeUnit.Month => "month"
    case TimeUnit.Year => "year"
    case _ => throw new QueryParsingException(s"No implementation is provided for timeunit function ${unit.toString}")
  }
  protected def parseGroupByFunc(groupBy: ByStatement, fieldExpr: String): String = {
    groupBy.funcOpt match {
      case Some(func) =>
        func match {
          case interval: Interval => {
            interval.unit match {
              case TimeUnit.Day=>s"to_char(cast($fieldExpr as ${timeUnitFuncMap(interval.unit)}),'yyyy-mm-dd')"
              case _ => s"extract(${timeUnitFuncMap(interval.unit)} from $fieldExpr)"
            }
          }
          case level: Level =>
            //TODO remove this data type
            val hierarchyField = groupBy.field.asInstanceOf[HierarchyField]
            val field = hierarchyField.levels.find(_._1 == level.levelTag).get
            s"$fieldExpr.${field._2}"

          case GeoCellTenth => parseGeoCell(1, fieldExpr, groupBy.field.dataType)
          case GeoCellHundredth => parseGeoCell(2, fieldExpr, groupBy.field.dataType)
          case GeoCellThousandth => parseGeoCell(3, fieldExpr, groupBy.field.dataType)
          case bin: Bin => s"$round($fieldExpr/${bin.scale})*${bin.scale}"
          case _ => throw new QueryParsingException(s"unknown function: ${func.name}")
        }
      case None => fieldExpr
    }
  }





  override protected def parseDrop(query: DropView, schemaMap: Map[String, AbstractSchema]): String = {


    s"""
       | declare
       |     result1 number(8);
       | begin
       |   select count(*)into result1 from dba_tables where owner = 'BERRY' and table_name = '${query.dataset}';
       | if result1 > 0 then
       |  execute immediate 'drop table $quote${query.dataset}$quote ';
       |  end if;
       |  end;
       |
       | """.stripMargin


  }



  //  override protected def parseQuery(query: Query, schemaMap: Map[String, AbstractSchema]): String = {
  //    val queryBuilder = new mutable.StringBuilder()
  //   println("1 parse query",query,schemaMap)
  //    val exprMap: Map[String, FieldExpr] = initExprMap(query.dataset, schemaMap)
  //
  //    val fromStr = s"from $quote${query.dataset}$quote $sourceVar".trim
  //    queryBuilder.append(fromStr)
  //    println("query.dataset",query.dataset)
  //    println(fromStr,"fromstr","query builder",queryBuilder)
  //    val resultAfterAppend = parseAppend(query.append, exprMap, queryBuilder)
  //
  //    val resultAfterLookup = parseLookup(query.lookup, resultAfterAppend.exprMap, queryBuilder, false)
  //
  //    val resultAfterUnnest = parseUnnest(query.unnest, resultAfterLookup.exprMap, queryBuilder)
  //    val unnestTests = resultAfterUnnest.strs
  //
  //    val resultAfterFilter = parseFilter(query.filter, resultAfterUnnest.exprMap, unnestTests, queryBuilder)
  //
  //    val resultAfterGroup = parseGroupby(query.groups, resultAfterFilter.exprMap, queryBuilder)
  //
  //    val resultAfterSelect = parseSelect(query.select, resultAfterGroup.exprMap, query, queryBuilder)
  //
  //    val resultAfterGlobalAggr = parseGlobalAggr(query.globalAggr, resultAfterSelect.exprMap, queryBuilder)
  //
  //    println("Oracle SQL Generator="+queryBuilder.toString())
  //
  //    queryBuilder.toString
  //  }


  /**
    * Process SDO_POINT_TYPE of ORACLE
    * : return POINT field as text to avoid messy code. https://dev.mysql.com/doc/refman/5.7/en/gis-format-conversion-functions.html
    * ST_X, ST_Y: get X/Y-coordinate of Point. https://dev.mysql.com/doc/refman/5.6/en/gis-point-property-functions.html
    * truncate: a number truncated to a certain number of decimal places, mainly used in groupBy. http://www.w3resource.com/mysql/mathematical-functions/mysql-truncate-function.php
    * @param scale
    * @param fieldExpr
    * @param dataType
    */
  def parseGeoCell(scale: Integer, fieldExpr: String, dataType: DataType.Value): String = {
    s"$geoAsText($dataType($truncate(${pointGetCoord(0)}($fieldExpr),$scale),$truncate(${pointGetCoord(1)}($fieldExpr),$scale))) "
  }

}

object OracleGenerator extends IQLGeneratorFactory {
  override def apply(): IQLGenerator = new OracleGenerator()
}
